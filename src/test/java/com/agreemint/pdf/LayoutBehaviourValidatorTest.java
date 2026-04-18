package com.agreemint.pdf;

import com.agreemint.api.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the v2 {@code rules[]} shape that was added alongside the unified editor — plus a
 * couple of regression guards so the legacy keys still pass and unknown keys still throw.
 */
class LayoutBehaviourValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsLegacyShapeWithoutRules() throws Exception {
        JsonNode layout = mapper.readTree("""
                {
                  "elements": [
                    {
                      "id": "t1",
                      "type": "TEXT",
                      "behaviour": {
                        "behaviourVersion": 1,
                        "visibilityRules": [{"when": {"left": "{{flag}}", "op": "eq", "right": "1"}, "show": true}],
                        "visibilityDefaultShow": false
                      }
                    }
                  ]
                }
                """);
        assertDoesNotThrow(() -> LayoutBehaviourValidator.validateLayoutElements(layout));
    }

    @Test
    void acceptsUnifiedRulesList() throws Exception {
        JsonNode layout = mapper.readTree("""
                {
                  "elements": [
                    {
                      "id": "a",
                      "type": "ARROW",
                      "behaviour": {
                        "behaviourVersion": 1,
                        "rules": [
                          {
                            "id": "r1",
                            "enabled": true,
                            "when": {
                              "kind": "all",
                              "of": [
                                {"kind": "compare", "left": "{{status}}", "op": "eq", "right": "overdue"},
                                {"kind": "any", "of": [
                                  {"kind": "compare", "left": "{{rowCount}}", "op": "gt", "right": 0}
                                ]}
                              ]
                            },
                            "action": {
                              "kind": "set",
                              "target": "fillColor",
                              "value": {"mode": "fixed", "value": "#ef4444"}
                            }
                          },
                          {
                            "id": "r2",
                            "action": {
                              "kind": "set",
                              "target": "width",
                              "value": {
                                "mode": "mapping",
                                "var": "status",
                                "cases": [{"match": "paid", "value": 200}],
                                "fallback": 100
                              }
                            }
                          },
                          {
                            "id": "r3",
                            "action": {"kind": "hide"}
                          }
                        ]
                      }
                    }
                  ]
                }
                """);
        assertDoesNotThrow(() -> LayoutBehaviourValidator.validateLayoutElements(layout));
    }

    @Test
    void rejectsRulesWithUnknownTarget() throws Exception {
        JsonNode layout = wrap("""
                {"id":"x","type":"BOX","behaviour":{"rules":[
                  {"id":"r","action":{"kind":"set","target":"warpDrive","value":{"mode":"fixed","value":42}}}
                ]}}
                """);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> LayoutBehaviourValidator.validateLayoutElements(layout));
        assertTrue(e.getMessage().contains("warpDrive"));
    }

    @Test
    void rejectsRulesWithUnknownActionKind() throws Exception {
        JsonNode layout = wrap("""
                {"id":"x","type":"BOX","behaviour":{"rules":[
                  {"id":"r","action":{"kind":"destroy"}}
                ]}}
                """);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> LayoutBehaviourValidator.validateLayoutElements(layout));
        assertTrue(e.getMessage().contains("kind"));
    }

    @Test
    void rejectsRulesWithUnknownValueMode() throws Exception {
        JsonNode layout = wrap("""
                {"id":"x","type":"BOX","behaviour":{"rules":[
                  {"id":"r","action":{"kind":"set","target":"width","value":{"mode":"vibes","value":1}}}
                ]}}
                """);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> LayoutBehaviourValidator.validateLayoutElements(layout));
        assertTrue(e.getMessage().contains("mode"));
    }

    @Test
    void rejectsMissingRuleId() throws Exception {
        JsonNode layout = wrap("""
                {"id":"x","type":"BOX","behaviour":{"rules":[
                  {"action":{"kind":"hide"}}
                ]}}
                """);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> LayoutBehaviourValidator.validateLayoutElements(layout));
        assertTrue(e.getMessage().contains(".id"));
    }

    @Test
    void rejectsUnknownTopLevelBehaviourKey() throws Exception {
        JsonNode layout = wrap("""
                {"id":"x","type":"BOX","behaviour":{"telepathy": true}}
                """);
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> LayoutBehaviourValidator.validateLayoutElements(layout));
        assertTrue(e.getMessage().contains("telepathy"));
    }

    /** Wrap a single-element JSON string in a valid layout envelope. */
    private JsonNode wrap(String element) throws Exception {
        return mapper.readTree("{\"elements\":[" + element + "]}");
    }
}
