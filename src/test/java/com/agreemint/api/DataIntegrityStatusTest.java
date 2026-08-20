package com.agreemint.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database refused a write — which is several different answers.
 *
 * <p>{@code DataIntegrityViolationException} is one class covering unrelated
 * failures, so the interesting property is not "it is no longer a 500" but that
 * each SQLSTATE gets the status a caller can act on: a duplicate is a 409 they
 * fix by renaming, an over-long value is a 400 they fix by shortening. A single
 * blanket mapping would swap one wrong answer for another.
 *
 * <p>The other half of the contract is what does NOT come back. Postgres puts
 * the table, the index and sometimes the offending value into its message.
 * Those describe our schema, and every test below checks they stay server-side.
 */
class DataIntegrityStatusTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /** Mirrors what Spring hands us: a translated wrapper over the driver's SQLException. */
    private static DataIntegrityViolationException violation(String sqlState, String dbMessage) {
        return new DataIntegrityViolationException(
                "could not execute statement", new SQLException(dbMessage, sqlState));
    }

    private ResponseEntity<Map<String, String>> handle(String sqlState, String dbMessage) {
        return handler.dataIntegrity(violation(sqlState, dbMessage));
    }

    @Test
    void springRoutesItHereAndNotToTheCatchAll() {
        Method chosen = new ExceptionHandlerMethodResolver(ApiExceptionHandler.class)
                .resolveMethod(violation("23505", "dup"));
        assertEquals("dataIntegrity", chosen.getName());
    }

    @Test
    void duplicateIs409WithAStableCode() {
        // e.g. two templates named the same in one workspace — UNIQUE (org_id, name)
        var r = handle("23505",
                "ERROR: duplicate key value violates unique constraint \"uq_templates_org_name\"");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("ALREADY_EXISTS", r.getBody().get("code"));
    }

    @Test
    void overlongValueIs400NotAConflict() {
        // A 300-character title into VARCHAR(256). The caller shortens it; there
        // is nothing to "conflict" with, so 409 would send them looking for a
        // clash that does not exist.
        var r = handle("22001", "ERROR: value too long for type character varying(256)");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().get("error").contains("too long"));
    }

    @Test
    void missingRequiredColumnIs400() {
        var r = handle("23502",
                "ERROR: null value in column \"title\" of relation \"marketplace_listings\"");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test
    void checkConstraintIs400() {
        assertEquals(HttpStatus.BAD_REQUEST, handle("23514", "violates check constraint").getStatusCode());
    }

    @Test
    void foreignKeyIs400() {
        var r = handle("23503", "ERROR: violates foreign key constraint \"fk_docs_template\"");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test
    void anUnrecognisedSqlstateStaysA500() {
        // If we cannot say what the caller did wrong, asserting they did
        // something wrong is a guess. An integrity failure we did not anticipate
        // is likelier to be our bug than theirs.
        var r = handle("40001", "ERROR: could not serialize access due to concurrent update");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
    }

    @Test
    void aNonSqlCauseStaysA500() {
        var e = new DataIntegrityViolationException("wrapped", new IllegalStateException("no sqlstate"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, handler.dataIntegrity(e).getStatusCode());
    }

    @Test
    void neverLeaksTheSchema() {
        // Constraint names, table names, column names, type widths — all of it
        // describes our database and none of it helps the caller.
        String[][] cases = {
                {"23505", "duplicate key value violates unique constraint \"uq_templates_public_slug\""},
                {"22001", "value too long for type character varying(256)"},
                {"23502", "null value in column \"title\" of relation \"marketplace_listings\""},
                {"23503", "violates foreign key constraint \"fk_docs_template\" on table \"documents\""},
        };
        for (String[] c : cases) {
            String body = handle(c[0], c[1]).getBody().get("error");
            for (String leak : new String[]{
                    "uq_", "fk_", "constraint", "relation", "column", "varying", "templates",
                    "marketplace_listings", "documents"}) {
                assertFalse(body.toLowerCase().contains(leak),
                        "SQLSTATE " + c[0] + " leaked \"" + leak + "\": " + body);
            }
        }
    }
}
