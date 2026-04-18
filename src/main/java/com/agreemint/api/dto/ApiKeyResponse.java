package com.agreemint.api.dto;

import com.agreemint.domain.ApiKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Metadata-only projection of an {@link ApiKey}. Never includes the raw key
 * value — that is only returned once, by {@link ApiKeyCreatedResponse}, at
 * the moment a new key is created or rotated.
 */
public record ApiKeyResponse(
        UUID id,
        UUID orgId,
        String name,
        String keyPrefix,
        String keyLast4,
        List<String> scopes,
        String allowedIps,
        int rateLimitRpm,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        String lastUsedIp,
        Instant revokedAt,
        UUID rotatedToId
) {
    public static ApiKeyResponse from(ApiKey k) {
        return new ApiKeyResponse(
                k.getId(),
                k.getOrgId(),
                k.getName(),
                k.getKeyPrefix(),
                k.getKeyLast4(),
                List.copyOf(k.scopeSet()),
                k.getAllowedIps(),
                k.getRateLimitRpm(),
                k.getCreatedAt(),
                k.getExpiresAt(),
                k.getLastUsedAt(),
                k.getLastUsedIp(),
                k.getRevokedAt(),
                k.getRotatedToId()
        );
    }
}
