package com.agreemint.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client mistakes must not be reported as server faults.
 *
 * <p>Each case here returned <b>500 "An internal error occurred. Please try
 * again later."</b> The status was wrong, and for an API client the remedy was
 * worse than wrong: the published docs prescribe retry-with-backoff for 5xx, so
 * a conforming client retries forever against a request that can never succeed.
 *
 * <p>Every test asserts through {@link ExceptionHandlerMethodResolver} — the
 * class Spring itself uses to pick a handler — as well as on the response.
 * Asserting only on a direct call is what let the first version of this suite
 * pass with the annotations deleted.
 */
class ClientErrorStatusTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);

    private void routesAwayFromCatchAll(Exception e, String expected) {
        Method chosen = resolver.resolveMethod(e);
        assertNotNull(chosen, e.getClass().getSimpleName() + " resolved to no handler");
        assertEquals(expected, chosen.getName(),
                e.getClass().getSimpleName() + " must not fall through to the 500 catch-all");
    }

    // ── 405 ──────────────────────────────────────────────────────────────────

    @Test
    void wrongVerbIs405AndCarriesAllow() {
        var e = new HttpRequestMethodNotSupportedException("GET", List.of("POST"));
        routesAwayFromCatchAll(e, "methodNotAllowed");

        ResponseEntity<Map<String, String>> r = handler.methodNotAllowed(e);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, r.getStatusCode());
        // Allow is mandatory on a 405 and was literally "null" before this.
        assertEquals(List.of(HttpMethod.POST), r.getHeaders().getAllow().stream().toList());
        assertTrue(r.getBody().get("error").contains("POST"), r.getBody().toString());
    }

    @Test
    void wrongVerbStillAnswersWhenSpringKnowsNoAlternatives() {
        var e = new HttpRequestMethodNotSupportedException("TRACE");
        ResponseEntity<Map<String, String>> r = handler.methodNotAllowed(e);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, r.getStatusCode());
        assertNotNull(r.getBody().get("error"));
    }

    // ── 415 ──────────────────────────────────────────────────────────────────

    @Test
    void wrongContentTypeIs415AndNamesWhatWeAccept() {
        var e = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_FORM_URLENCODED, List.of(MediaType.APPLICATION_JSON));
        routesAwayFromCatchAll(e, "unsupportedMediaType");

        ResponseEntity<Map<String, String>> r = handler.unsupportedMediaType(e);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, r.getStatusCode());
        assertEquals(List.of(MediaType.APPLICATION_JSON), r.getHeaders().getAccept());
        assertTrue(r.getBody().get("error").contains("application/json"));
    }

    @Test
    void neverEchoesTheCallerSuppliedContentType() {
        // The value is caller-controlled and ends up in logs and in whatever
        // renders the error string, so the message names only what we accept.
        //
        // Note the first draft of this test used a subtype containing "<".
        // MediaType refuses to construct one — invalid token character — so
        // markup cannot reach this handler through that field at all. Spring
        // sanitises upstream; this asserts the weaker but real property, that a
        // well-formed type the caller chose is still not reflected back.
        var caller = new MediaType("application", "vnd.caller-chose-this+json");
        var e = new HttpMediaTypeNotSupportedException(caller, List.of(MediaType.APPLICATION_JSON));

        String body = handler.unsupportedMediaType(e).getBody().get("error");

        assertFalse(body.contains("caller-chose-this"), body);
        assertTrue(body.contains("application/json"), body);
    }

    // ── 413 / 400 on uploads ────────────────────────────────────────────────

    @Test
    void oversizeUploadIs413() {
        var e = new MaxUploadSizeExceededException(3L * 1024 * 1024);
        routesAwayFromCatchAll(e, "tooLarge");
        var r = handler.tooLarge(e);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, r.getStatusCode());
        assertTrue(r.getBody().get("error").contains("3 MB"), r.getBody().toString());
    }

    @Test
    void unparsableMultipartIs400NotThe413() {
        // MaxUploadSizeExceededException extends MultipartException, so handler
        // ordering matters: the more specific one must win.
        routesAwayFromCatchAll(new MultipartException("no boundary"), "badMultipart");
        routesAwayFromCatchAll(new MaxUploadSizeExceededException(1), "tooLarge");
    }

    // ── 409 on a lost race ──────────────────────────────────────────────────

    @Test
    void concurrentSaveIs409WithAStableCode() {
        var e = new ObjectOptimisticLockingFailureException("TemplateDraft", "id-123");
        // Declared on the superclass so all three @Version entities are covered.
        routesAwayFromCatchAll(e, "staleWrite");

        ResponseEntity<Map<String, String>> r =
                handler.staleWrite((OptimisticLockingFailureException) e);
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("STALE_VERSION", r.getBody().get("code"),
                "the console needs a stable code to reload-and-reapply on");
    }

    @Test
    void staleWriteDoesNotLeakTheEntityOrItsId() {
        // Hibernate's message names the entity class and primary key.
        var e = new ObjectOptimisticLockingFailureException("TemplateDraft", "id-123");
        String body = handler.staleWrite((OptimisticLockingFailureException) e).getBody().get("error");
        assertFalse(body.contains("TemplateDraft"), body);
        assertFalse(body.contains("id-123"), body);
    }

    // ── the shared contract ─────────────────────────────────────────────────

    @Test
    void noneOfThemSaysTryAgainLater() {
        // The catch-all's wording. On every exception here it is false: the
        // request is refused, and repeating it unchanged cannot help.
        List<Exception> all = List.of(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")),
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                new MaxUploadSizeExceededException(1),
                new MultipartException("bad"),
                new ObjectOptimisticLockingFailureException("T", "1"));

        for (Exception e : all) {
            Method m = resolver.resolveMethod(e);
            assertNotEqualsGeneric(m, e);
        }
    }

    private static void assertNotEqualsGeneric(Method m, Exception e) {
        assertNotNull(m, e.getClass().getSimpleName() + " has no handler");
        assertFalse("generic".equals(m.getName()),
                e.getClass().getSimpleName() + " still falls through to the 500 catch-all");
    }
}
