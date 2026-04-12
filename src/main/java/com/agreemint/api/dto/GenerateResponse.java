package com.agreemint.api.dto;

import java.util.UUID;

public record GenerateResponse(
        UUID documentId,
        String fileUrl
) {
}
