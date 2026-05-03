package com.agreemint.collab;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Structural editor operations exchanged over STOMP on
 * {@code /app/template/{id}/op} and rebroadcast on {@code /topic/template/{id}/ops}.
 *
 * <p>Payloads are intentionally typed but {@link JsonNode}-valued for the mutable parts —
 * the server is an ordering/persistence relay and does not need to understand the shape of
 * {@code element}, {@code patch} or {@code page} beyond treating them as opaque JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CollabOp.AddElement.class, name = "addElement"),
        @JsonSubTypes.Type(value = CollabOp.DeleteElements.class, name = "deleteElements"),
        @JsonSubTypes.Type(value = CollabOp.UpdateElement.class, name = "updateElement"),
        @JsonSubTypes.Type(value = CollabOp.BulkUpdateElements.class, name = "bulkUpdateElements"),
        @JsonSubTypes.Type(value = CollabOp.AddPage.class, name = "addPage"),
        @JsonSubTypes.Type(value = CollabOp.DeletePage.class, name = "deletePage"),
        @JsonSubTypes.Type(value = CollabOp.ReorderPages.class, name = "reorderPages"),
        @JsonSubTypes.Type(value = CollabOp.UpdatePage.class, name = "updatePage"),
        @JsonSubTypes.Type(value = CollabOp.SetGlobalVariables.class, name = "setGlobalVariables"),
        @JsonSubTypes.Type(value = CollabOp.SetPageVariables.class, name = "setPageVariables"),
        @JsonSubTypes.Type(value = CollabOp.SetPageSpec.class, name = "setPageSpec"),
        @JsonSubTypes.Type(value = CollabOp.RenameGlobalVariable.class, name = "renameGlobalVariable"),
})
public sealed interface CollabOp permits
        CollabOp.AddElement,
        CollabOp.DeleteElements,
        CollabOp.UpdateElement,
        CollabOp.BulkUpdateElements,
        CollabOp.AddPage,
        CollabOp.DeletePage,
        CollabOp.ReorderPages,
        CollabOp.UpdatePage,
        CollabOp.SetGlobalVariables,
        CollabOp.SetPageVariables,
        CollabOp.SetPageSpec,
        CollabOp.RenameGlobalVariable {

    record AddElement(int pageIndex, JsonNode element) implements CollabOp {}

    record DeleteElements(int pageIndex, List<String> elementIds) implements CollabOp {}

    record UpdateElement(int pageIndex, String elementId, JsonNode patch) implements CollabOp {}

    record BulkUpdateElements(int pageIndex, List<ElementPatch> updates) implements CollabOp {
        public record ElementPatch(String elementId, JsonNode patch) {}
    }

    record AddPage(int index, JsonNode page) implements CollabOp {}

    record DeletePage(int index) implements CollabOp {}

    record ReorderPages(int from, int to) implements CollabOp {}

    record UpdatePage(int pageIndex, JsonNode patch) implements CollabOp {}

    record SetGlobalVariables(JsonNode variables) implements CollabOp {}

    record SetPageVariables(int pageIndex, JsonNode variables) implements CollabOp {}

    /**
     * Template-wide {@code pageSpec} (size / margins / orientation). Lives at the
     * root of the layout JSON as the {@code page} field (see {@code buildLayoutJson}),
     * not inside {@code pages[i]}.
     */
    record SetPageSpec(JsonNode pageSpec) implements CollabOp {}

    /**
     * Atomic global-variable rename. Pairs the variables-array key change
     * with a walk of every element's {@code dataKey} field to update the
     * binding in the same op so a peer's concurrent UpdateElement
     * carrying the old key cannot land between the rename and the binding
     * fix. Server applies the rename idempotently — if {@code oldKey}
     * isn't found in the variables array (e.g. because per-keystroke
     * SetGlobalVariables ops already replaced it), the dataKey walk
     * still runs so any straggler bindings get repointed.
     */
    record RenameGlobalVariable(String oldKey, String newKey) implements CollabOp {}
}
