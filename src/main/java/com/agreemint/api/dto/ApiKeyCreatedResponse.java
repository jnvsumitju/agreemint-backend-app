package com.agreemint.api.dto;

/**
 * Response shape returned <em>once</em>, at the moment an API key is created or
 * rotated. Carries the raw secret string — after this response the server has
 * no way to reconstruct it. The client is expected to show it to the user with
 * a copy button and "we won't show you this again" messaging.
 */
public record ApiKeyCreatedResponse(
        /** Metadata — same shape the list endpoint returns. */
        ApiKeyResponse key,
        /** The raw secret. Starts with {@code ak_live_}. */
        String rawKey
) {}
