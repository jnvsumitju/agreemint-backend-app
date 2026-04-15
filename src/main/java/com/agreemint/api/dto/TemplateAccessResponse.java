package com.agreemint.api.dto;

public record TemplateAccessResponse(
        String role,
        boolean canEdit,
        boolean canComment
) {}
