package com.agreemint.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param sha256 Lowercase hex SHA-256 of the generated PDF, or null when
 *               generation failed. This is the digest of exactly the bytes the
 *               download endpoints serve, so an integrator can store it and
 *               later prove a file in their possession is unmodified — either
 *               by comparing locally, or via the public verification endpoint.
 * @param warnings Placeholder paths in the template that the supplied {@code data}
 *                 did not fill. Always present, empty when everything resolved.
 *
 *                 <p>An unresolved placeholder renders as an empty string, so
 *                 without this a mistyped key produces a structurally perfect
 *                 PDF with a blank where a value belongs and nothing to
 *                 indicate it. The document is still generated — this reports,
 *                 it does not reject — because refusing a render for a blank
 *                 optional field would break templates that omit values on
 *                 purpose.
 *
 *                 <p>Always emitted, never null and never omitted. An absent
 *                 key would make "nothing was missing" indistinguishable from
 *                 "this server does not compute warnings", which is the same
 *                 ambiguity the field exists to remove.
 *
 *                 <p>Scalar placeholders only, and only ones the renderer
 *                 recognises: a placeholder containing a pipe or a hyphen is
 *                 not matched by the substitution engine at all, prints
 *                 literally, and will not appear here. Capped at 25.
 */
public record GenerateResponse(
        UUID documentId,
        String fileUrl,
        String sha256,
        List<String> warnings
) {
}
