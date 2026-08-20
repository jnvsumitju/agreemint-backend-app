package com.agreemint.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a variable PATCH apart from a whole variable MAP.
 *
 * <p>The endpoint has to accept both. A tab left open across a deploy keeps
 * sending the full map and must not start failing, while new clients send
 * {@code {"set":…,"remove":…}} so concurrent editors stop erasing each other.
 *
 * <p>The discrimination is the risky part. Guessing on key names alone would
 * misread a template whose author happened to name a variable {@code set} — the
 * whole map would be treated as a patch, and every other variable in it
 * silently dropped. So the check is on VALUE TYPE and on there being nothing
 * else in the body, and both halves are pinned here.
 */
class VariablePatchMergeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static boolean isPatch(String json) throws Exception {
        Method m = TemplateController.class.getDeclaredMethod("isPatch", JsonNode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, M.readTree(json));
    }

    @Test
    void aPatchIsRecognised() throws Exception {
        assertTrue(isPatch("{\"set\":{\"a\":\"1\"},\"remove\":[\"b\"]}"));
        assertTrue(isPatch("{\"set\":{\"a\":\"1\"}}"));
        assertTrue(isPatch("{\"remove\":[\"b\"]}"));
    }

    @Test
    void anOrdinaryVariableMapIsNot() throws Exception {
        assertFalse(isPatch("{\"company.name\":\"Acme\",\"invoice.total\":\"100\"}"));
        assertFalse(isPatch("{}"));
    }

    @Test
    void aVariableNamedSetDoesNotMasqueradeAsAPatch() throws Exception {
        // The sharp edge. A variable called "set" holds a STRING; a patch's
        // "set" is an object. Reading this as a patch would drop every other
        // variable in the template.
        assertFalse(isPatch("{\"set\":\"a string value\"}"));
        assertFalse(isPatch("{\"set\":\"v\",\"other\":\"w\"}"));
    }

    @Test
    void aMapCarryingSetAlongsideRealVariablesIsNotAPatch() throws Exception {
        // Even with an object value, extra fields mean it cannot be a patch —
        // they would be silently discarded.
        assertFalse(isPatch("{\"set\":{\"a\":\"1\"},\"company.name\":\"Acme\"}"));
    }

    @Test
    void malformedBodiesAreTreatedAsMaps() throws Exception {
        // Falls back to the legacy path, which already tolerates them.
        assertFalse(isPatch("[]"));
        assertFalse(isPatch("\"nope\""));
        assertFalse(isPatch("null"));
    }
}
