package com.agreemint.api;

import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;

import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller sees when their API key lacks a scope.
 *
 * <p>This existed as a 500 with the body "An internal error occurred. Please
 * try again later." — the catch-all, because nothing handled
 * {@link AuthorizationDeniedException}. Three separate things were wrong with
 * that and each is asserted below: the status contradicted the published docs,
 * a refusal was reported as a server fault, and the remedy offered was to
 * retry, which cannot ever work because the key's scopes do not change.
 */
class ScopeDeniedResponseTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static AuthorizationDeniedException deniedFor(String expression) {
        return new AuthorizationDeniedException(
                "Access Denied",
                new ExpressionAuthorizationDecision(
                        false, new SpelExpressionParser().parseExpression(expression)));
    }

    private String bodyFor(AuthorizationDeniedException e) {
        ResponseEntity<Map<String, String>> r = handler.forbidden(e);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode(), "must be 403, as the docs promise");
        return r.getBody().get("error");
    }

    /**
     * The routing, not the wording — and the reason the rest of this class is
     * not enough on its own.
     *
     * <p>Every other test here calls {@code handler.forbidden(e)} directly, so
     * they all pass whether or not the {@code @ExceptionHandler} annotation
     * exists. Deleting it reproduces the original bug exactly — Spring falls
     * back to the {@code Exception} catch-all and the caller gets the 500 — and
     * not one of those tests notices, because none of them asks Spring which
     * method it would choose.
     *
     * <p>{@link ExceptionHandlerMethodResolver} is the class Spring itself uses
     * to make that choice, so asking it is the same question the framework asks
     * at runtime.
     */
    @Test
    void springRoutesTheExceptionHereAndNotToTheCatchAll() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);

        Method chosen = resolver.resolveMethod(deniedFor("hasAuthority('SCOPE_documents:read')"));

        assertEquals("forbidden", chosen.getName(),
                "AuthorizationDeniedException must resolve to the 403 handler, not the 500 catch-all");
    }

    @Test
    void namesTheMissingScope() {
        String body = bodyFor(deniedFor("hasAuthority('SCOPE_documents:read')"));
        assertTrue(body.contains("documents:read"),
                "a permissions error that will not say which permission is not useful: " + body);
    }

    @Test
    void tellsThemRetryingWillNotHelp() {
        // The old 500 said "Please try again later". For a missing scope that is
        // not merely unhelpful, it is false.
        String body = bodyFor(deniedFor("hasAuthority('SCOPE_documents:generate')"));
        assertTrue(body.contains("Retrying with this key will not help"), body);
    }

    @Test
    void pointsAtCreatingAKeyRatherThanEditingOne() {
        // ApiKeyController has create/list/revoke/rotate and no update, so
        // scopes cannot be added to an existing key. Telling someone to edit it
        // would send them looking for a control that does not exist.
        String body = bodyFor(deniedFor("hasAuthority('SCOPE_templates:read')"));
        assertTrue(body.contains("create a new key"), body);
        assertTrue(body.contains("Settings"), body);
    }

    @Test
    void staysA403WhenTheScopeCannotBeParsed() {
        // Any decision shape that is not expression-based, e.g. a future Spring
        // Security change. Losing the scope name must degrade the message, not
        // resurrect the 500.
        AuthorizationDeniedException e =
                new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
        String body = bodyFor(e);
        assertTrue(body.contains("not permitted"), body);
        assertFalse(body.contains("internal error"), "must never fall back to the catch-all wording");
    }

    @Test
    void neverLeaksTheRawSpringMessage() {
        // "Access Denied" tells a developer nothing, and the expression string
        // is an implementation detail of ours.
        String body = bodyFor(deniedFor("hasAuthority('SCOPE_documents:read')"));
        assertFalse(body.contains("hasAuthority"), body);
        assertFalse(body.equals("Access Denied"), body);
    }
}
