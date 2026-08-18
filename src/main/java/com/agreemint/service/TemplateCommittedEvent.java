package com.agreemint.service;

import java.util.UUID;

/**
 * A new template version has been written.
 *
 * <p>Carries ids only, deliberately. Handlers run after the commit transaction
 * has closed, and by then the draft row this version was made from has been
 * deleted — so anything derived from the draft has to be re-read from
 * {@code template_versions}, which is the durable record and cannot disagree
 * with what was actually committed.
 *
 * <p>Passing the layout and variables by value would work too, but they are
 * mutable {@code JsonNode}s handed to another thread, and they can differ from
 * the version that was stored: {@code createVersion} substitutes a default
 * layout when the draft's is null, so the event payload and the row would
 * describe different documents in exactly the case that is hardest to notice.
 */
public record TemplateCommittedEvent(UUID templateId, UUID versionId) {
}
