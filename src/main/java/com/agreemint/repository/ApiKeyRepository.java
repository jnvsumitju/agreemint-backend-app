package com.agreemint.repository;

import com.agreemint.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Looks up a key by its sha256 hash. Used on the auth hot path, once per
     * incoming request with {@code X-Api-Key}. Partial index covers active keys.
     */
    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /**
     * Used by the expiry-warning job to send a 7-day warning exactly once.
     * Callers narrow further by comparing {@code last_warned_at} in memory;
     * keeping the query broad (just the window) keeps the index small.
     */
    List<ApiKey> findByRevokedAtIsNullAndExpiresAtBetween(
            java.time.Instant from, java.time.Instant to);
}
