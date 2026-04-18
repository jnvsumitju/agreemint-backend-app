package com.agreemint.api.dto;

import java.util.List;

/**
 * Request body for creating a new API key.
 *
 * @param name           human label shown in Settings → Developer
 * @param scopes         wire-name scopes (see {@code ApiKeyScope})
 * @param expiresInDays  {@code null} means never expires
 * @param allowedIps     comma-separated CIDR list or {@code null} for any
 * @param rateLimitRpm   per-minute request cap; falls back to server default when null
 */
public record CreateApiKeyRequest(
        String name,
        List<String> scopes,
        Integer expiresInDays,
        String allowedIps,
        Integer rateLimitRpm
) {}
