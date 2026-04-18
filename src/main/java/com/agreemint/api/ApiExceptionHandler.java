package com.agreemint.api;

import com.agreemint.service.ReviewBlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

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
    public ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid value: " + e.getMessage()));
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

    /** Catch-all: log the error but do NOT leak exception details to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An internal error occurred. Please try again later."));
    }
}
