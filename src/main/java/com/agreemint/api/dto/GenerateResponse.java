package com.agreemint.api.dto;

import java.util.UUID;

/**
 * @param sha256 Lowercase hex SHA-256 of the generated PDF, or null when
 *               generation failed. This is the digest of exactly the bytes the
 *               download endpoints serve, so an integrator can store it and
 *               later prove a file in their possession is unmodified — either
 *               by comparing locally, or via the public verification endpoint.
 */
public record GenerateResponse(
        UUID documentId,
        String fileUrl,
        String sha256
) {
}
