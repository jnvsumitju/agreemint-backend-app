package com.agreemint.api;

import com.agreemint.service.ReviewBlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.regex.Matcher;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", msg));
    }

    /** Handle invalid enum values, UUID parsing, etc. — return 400 instead of 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Invalid value: " + e.getMessage(),
                "code", "INVALID_ARGUMENT"
        ));
    }

    /**
     * Path / query parameter failed type conversion (e.g. non-UUID supplied
     * where a UUID is required). Spring wraps the underlying
     * {@link IllegalArgumentException} so the generic handler above doesn't
     * catch it — map it explicitly to a 400 that names the offending parameter.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        String type = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "value";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Parameter '" + e.getName() + "' must be a valid " + type
                        + " (got: " + e.getValue() + ")",
                "code", "INVALID_PARAMETER",
                "parameter", e.getName()
        ));
    }

    /** Missing required query / form parameter. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Missing required parameter '" + e.getParameterName() + "'",
                "code", "MISSING_PARAMETER",
                "parameter", e.getParameterName()
        ));
    }

    /** Malformed / empty JSON body — avoids a 500 from Jackson bubbling up. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Request body could not be parsed as JSON",
                "code", "MALFORMED_BODY"
        ));
    }

    /**
     * Commit gate: {@link ReviewBlockException} is mapped to 409 Conflict with a
     * structured body so the frontend can surface the blocking reviews and offer
     * dismiss / reopen actions.
     */
    @ExceptionHandler(ReviewBlockException.class)
    public ResponseEntity<Map<String, Object>> reviewBlocked(ReviewBlockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "code", "REVIEW_BLOCK",
                "blockers", e.blockers()
        ));
    }

    /** Handle Spring's ResponseStatusException (used throughout services). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() != null ? e.getReason() : "Request failed"));
    }

    /**
     * Unknown path (probes, scanners, root `/`, stale frontend URLs). Return a
     * quiet 404 instead of letting it fall through to the catch-all, which
     * would log every hit at ERROR level and masquerade as a 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found"));
    }

    /**
     * Pulls {@code documents:read} out of {@code hasAuthority('SCOPE_documents:read')}.
     *
     * <p>Best-effort by design. If Spring Security ever changes how a denied
     * decision is represented, the caller still gets a correct 403 with the
     * generic wording rather than a 500 — losing the scope name is a worse
     * message, not a broken one.
     */
    private static final Pattern REQUIRED_SCOPE =
            Pattern.compile("hasAuthority\\('SCOPE_([^']+)'\\)");

    /**
     * An API key reached an endpoint it lacks the scope for.
     *
     * <p>Without this the exception fell through to the catch-all below and the
     * caller got <b>500 "An internal error occurred. Please try again later."</b>
     * — the single worst answer available. It is the wrong status (the request
     * was understood and refused, not broken), it contradicts the published
     * docs, which promise 403 for exactly this case, and it tells a developer
     * to retry something that will never succeed no matter how many times they
     * try it. The scope is missing; only a different key fixes that.
     *
     * <p>Naming the missing scope is deliberate and safe. It describes the
     * caller's own credential, not anything about our data or another tenant,
     * the scope names are published in the developer docs, and it is what
     * OAuth's {@code insufficient_scope} does for the same reason: the whole
     * point of a permissions error is to say which permission.
     *
     * <p>Method security is used ONLY on the v1 public API, and every
     * expression there is a scope check — the admin surface is gated by
     * request matchers in {@code SecurityConfig}, which Spring handles before
     * this advice is reached. So this handler can speak in terms of API keys
     * without being wrong somewhere else. If method security is ever added to
     * another surface, revisit the wording here.
     *
     * <p>The remedy names creating a key rather than editing one on purpose:
     * {@code ApiKeyController} exposes create, list, revoke and rotate, and no
     * update. Scopes are fixed when the key is minted, and rotate reissues the
     * secret without touching them.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AuthorizationDeniedException e) {
        String scope = requiredScope(e);
        String message = scope == null
                ? "This API key is not permitted to perform this action. Check the key's scopes "
                        + "in Settings \u2192 Developer."
                : "This API key does not have the \"" + scope + "\" scope, which this endpoint "
                        + "requires. Scopes are fixed when a key is created, so create a new key "
                        + "with \"" + scope + "\" in Settings \u2192 Developer and use that one. "
                        + "Retrying with this key will not help.";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", message));
    }

    private static String requiredScope(AuthorizationDeniedException e) {
        try {
            if (e.getAuthorizationResult() instanceof ExpressionAuthorizationDecision decision) {
                Matcher m = REQUIRED_SCOPE.matcher(decision.getExpression().getExpressionString());
                if (m.find()) return m.group(1);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the generic wording; see the pattern's note.
        }
        return null;
    }

    /**
     * Wrong verb on a real route.
     *
     * <p>Returned 500 with {@code Allow: null} — verified, not assumed. Two
     * things follow from that. The status told a caller our server was broken
     * when the request had merely been understood and refused, and 405 appears
     * nowhere in the published error table, so a client written to our contract
     * treats it as a 5xx and follows the documented 5xx advice: retry with
     * backoff, forever.
     *
     * <p>{@code Allow} is mandatory on a 405 per RFC 9110 and is the entire
     * payload here — it turns "something is wrong" into a five-second fix. The
     * caller's own verb is left out of the message because it adds nothing they
     * do not already know.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        Set<org.springframework.http.HttpMethod> allowed = e.getSupportedHttpMethods();
        HttpHeaders headers = new HttpHeaders();
        if (allowed != null && !allowed.isEmpty()) headers.setAllow(allowed);
        String verbs = allowed == null ? "" : allowed.stream()
                .map(org.springframework.http.HttpMethod::name).sorted().collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(Map.of("error", verbs.isEmpty()
                        ? "That HTTP method is not supported on this endpoint."
                        : "That HTTP method is not supported on this endpoint. Use " + verbs + "."));
    }

    /**
     * Right verb, wrong {@code Content-Type}.
     *
     * <p>The most likely way to get the quickstart wrong — omit
     * {@code -H 'Content-Type: application/json'} and curl sends
     * {@code application/x-www-form-urlencoded}. What made this one especially
     * bad is the contrast with its neighbour: MALFORMED json already gets a
     * clean 400 saying so, while VALID json under the wrong header got
     * "an internal error occurred, please try again later". The clearer message
     * went to the worse mistake.
     *
     * <p>Deliberately does not echo the offending {@code Content-Type} back.
     * It is attacker-controlled, it lands in logs and in whatever renders the
     * error string, and naming what we DO accept is both safer and more useful.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        List<MediaType> supported = e.getSupportedMediaTypes();
        String types = supported.isEmpty()
                ? MediaType.APPLICATION_JSON_VALUE
                : supported.stream().map(MediaType::toString).collect(Collectors.joining(", "));
        HttpHeaders headers = new HttpHeaders();
        if (!supported.isEmpty()) headers.setAccept(supported);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(headers)
                .body(Map.of("error", "This endpoint requires Content-Type: " + types
                        + ". Set the header and resend — retrying without it will not help."));
    }

    /**
     * Upload over the multipart ceiling.
     *
     * <p>Thrown by {@code DispatcherServlet.checkMultipart} before any
     * controller runs, which is why {@code AvatarController}'s own size check
     * could never fire: its limit is byte-identical to the container's, so
     * Spring rejects the request first. The comment there claimed that check
     * produced "a clean 400 instead of Spring's MaxUploadSizeExceededException
     * → 500". The opposite was true, and this handler is what makes the
     * intention real.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "That file is too large. Uploads are limited to 3 MB — "
                        + "resize the image and try again."));
    }

    /**
     * Multipart body that could not be parsed at all — usually a
     * {@code multipart/form-data} content type with no {@code boundary}.
     * A malformed request, so 400 rather than the 413 above.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> badMultipart(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "The upload could not be read as a multipart form. "
                        + "Check the Content-Type header includes a boundary."));
    }

    /**
     * Two writers raced the same row. Not a fault — a conflict.
     *
     * <p>Reachable on all three {@code @Version} entities, and routinely on
     * {@code TemplateDraft}: {@code CollabFlushJob} writes that row every five
     * seconds for as long as anyone is editing, while the console's debounced
     * variable save writes it too. The version is row-level, so it does not
     * help that the two touch different columns.
     *
     * <p>The asymmetry is what made this worth fixing. The flush side catches
     * and logs, losing quietly by design; only the HTTP side surfaced, so a
     * silent background autosave reported a server outage and the user's typed
     * values were dropped with nothing they could act on. 409 plus a stable
     * {@code code} lets the console reload and reapply instead of guessing.
     *
     * <p>Logged at INFO. This is expected traffic under concurrent editing, and
     * logging it as ERROR would bury real faults in noise. Hibernate's message
     * names the entity class and its primary key, so it is logged and not
     * shipped.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> staleWrite(OptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Someone else saved a change to this while you were editing. "
                        + "Reload to get the latest version, then reapply your change.",
                "code", "STALE_VERSION"));
    }

    // Postgres SQLSTATE classes, spelled out because the numbers are unreadable.
    // https://www.postgresql.org/docs/current/errcodes-appendix.html
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String VALUE_TOO_LONG = "22001";

    /**
     * The database refused a write.
     *
     * <p><b>Discriminated by SQLSTATE rather than mapped wholesale</b>, because
     * {@code DataIntegrityViolationException} is not one failure. A duplicate
     * name is a 409 the caller resolves by choosing another; an over-long value
     * is a 400 they resolve by shortening it; a broken foreign key is a 400
     * naming something that is not there. Collapsing all of them into one
     * status would replace a wrong answer with a differently wrong answer.
     *
     * <p>These are genuinely reachable. The schema has client-facing uniqueness
     * on template name per workspace, public slug, user email, workspace slug,
     * membership and pending invitations, and roughly sixty length-limited
     * VARCHAR columns that a long title or description will overflow.
     *
     * <p><b>Nothing from the database reaches the caller.</b> Not the message,
     * not the constraint name, not the column. Those describe our schema — the
     * table, the index, sometimes the offending value — and a caller neither
     * needs them nor should have them. The full exception is logged instead, so
     * support can still answer "which constraint".
     *
     * <p>An unrecognised SQLSTATE deliberately stays a 500. If we cannot say
     * what the caller did wrong, claiming they did something wrong is a guess,
     * and an integrity failure we did not anticipate is more likely our bug
     * than theirs.
     *
     * <p>Note {@code BillingService} and {@code SlugService} catch this locally
     * for cases they can resolve — a lost checkout race, a slug collision they
     * retry around. Those never reach here, by design; this is the backstop for
     * everything else.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException e) {
        String state = sqlState(e);

        if (UNIQUE_VIOLATION.equals(state)) {
            log.info("Unique constraint violation: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Something with that name or identifier already exists here. "
                            + "Choose a different one.",
                    "code", "ALREADY_EXISTS"));
        }
        if (VALUE_TOO_LONG.equals(state)) {
            log.info("Value too long for column: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "One of the values in the request is too long for the field it "
                            + "belongs to. Shorten it and try again."));
        }
        if (NOT_NULL_VIOLATION.equals(state) || CHECK_VIOLATION.equals(state)) {
            log.info("Constraint violation ({}): {}", state, e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "A required field is missing or holds a value that is not allowed."));
        }
        if (FOREIGN_KEY_VIOLATION.equals(state)) {
            log.info("Foreign key violation: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "The request refers to something that does not exist, or to "
                            + "something still in use elsewhere."));
        }

        // Unrecognised — treat as ours until proven otherwise. ERROR, not INFO.
        log.error("Unmapped data integrity violation (SQLSTATE {})", state, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "An internal error occurred. Please try again later."));
    }

    /**
     * SQLSTATE from the innermost cause, or null.
     *
     * <p>{@code getMostSpecificCause} unwraps Spring's translation and
     * Hibernate's wrapper in one step, and works whatever the driver, so this
     * does not depend on a Postgres type being on the classpath.
     */
    private static String sqlState(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /** Catch-all: log the error but do NOT leak exception details to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An internal error occurred. Please try again later."));
    }
}
