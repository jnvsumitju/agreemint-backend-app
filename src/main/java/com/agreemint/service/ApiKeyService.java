package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.ApiKeyCreatedResponse;
import com.agreemint.api.dto.ApiKeyResponse;
import com.agreemint.api.dto.CreateApiKeyRequest;
import com.agreemint.domain.ApiKey;
import com.agreemint.domain.ApiKeyScope;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.security.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Create / list / revoke / rotate API keys + a best-effort
 * {@link #touch(UUID, String)} called from the auth filter.
 *
 * <p>Raw key format: {@code ak_live_} + 40 lowercase hex chars (160 bits from
 * {@link SecureRandom}). Plenty of entropy while still compact enough to paste
 * into a terminal.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String PREFIX = "ak_live";
    private static final int RAW_BYTES = 20; // → 40 hex chars

    private final ApiKeyRepository repo;
    private final ActivityService activityService;

    public ApiKeyService(ApiKeyRepository repo, ActivityService activityService) {
        this.repo = repo;
        this.activityService = activityService;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Transactional
    public ApiKeyCreatedResponse create(UUID orgId, UUID createdBy, String createdByName,
                                         CreateApiKeyRequest req) {
        String name = safeName(req.name());
        Set<String> scopes = validateScopes(req.scopes());
        Instant expiresAt = req.expiresInDays() != null && req.expiresInDays() > 0
                ? Instant.now().plus(req.expiresInDays(), ChronoUnit.DAYS)
                : null;
        int rateLimit = req.rateLimitRpm() != null && req.rateLimitRpm() > 0
                ? req.rateLimitRpm()
                : 120;

        String rawKey = generateRawKey();
        ApiKey k = new ApiKey();
        k.setOrgId(orgId);
        k.setCreatedBy(createdBy);
        k.setName(name);
        k.setKeyHash(HashUtils.sha256(rawKey));
        k.setKeyPrefix(PREFIX);
        k.setKeyLast4(rawKey.substring(rawKey.length() - 4));
        k.setScopes(String.join(",", scopes));
        k.setAllowedIps(blankToNull(req.allowedIps()));
        k.setRateLimitRpm(rateLimit);
        k.setExpiresAt(expiresAt);
        ApiKey saved = repo.save(k);

        activityService.log(orgId, createdBy, createdByName, "API_KEY_CREATED",
                "API_KEY", saved.getId(), saved.getName());

        return new ApiKeyCreatedResponse(ApiKeyResponse.from(saved), rawKey);
    }

    /**
     * Rotation = create a successor key with the same scopes, and schedule the
     * old key to expire at {@code now + graceDays}. Customer integrations can
     * swap over to the new key during the grace window.
     */
    @Transactional
    public ApiKeyCreatedResponse rotate(UUID orgId, UUID actorId, String actorName,
                                         UUID keyId, int graceDays) {
        ApiKey old = repo.findById(keyId)
                .orElseThrow(() -> new NotFoundException("API key not found"));
        if (!old.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your org");
        }
        if (old.getRevokedAt() != null) {
            throw new BadRequestException("Key is revoked; create a new one instead");
        }
        if (graceDays < 1 || graceDays > 30) {
            throw new BadRequestException("graceDays must be between 1 and 30");
        }

        CreateApiKeyRequest req = new CreateApiKeyRequest(
                old.getName() + " (rotated)",
                List.copyOf(old.scopeSet()),
                null,                 // new key inherits no expiry unless caller sets one
                old.getAllowedIps(),
                old.getRateLimitRpm()
        );
        ApiKeyCreatedResponse created = create(orgId, actorId, actorName, req);

        Instant graceEnd = Instant.now().plus(graceDays, ChronoUnit.DAYS);
        // If the old key already had a sooner expiry, keep that.
        Instant effective = old.getExpiresAt() != null && old.getExpiresAt().isBefore(graceEnd)
                ? old.getExpiresAt() : graceEnd;
        old.setExpiresAt(effective);
        old.setRotatedToId(created.key().id());
        repo.save(old);

        activityService.log(orgId, actorId, actorName, "API_KEY_ROTATED",
                "API_KEY", old.getId(), old.getName());
        return created;
    }

    @Transactional
    public void revoke(UUID orgId, UUID actorId, String actorName, UUID keyId) {
        ApiKey k = repo.findById(keyId)
                .orElseThrow(() -> new NotFoundException("API key not found"));
        if (!k.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your org");
        }
        if (k.getRevokedAt() != null) return; // idempotent
        k.setRevokedAt(Instant.now());
        repo.save(k);
        activityService.log(orgId, actorId, actorName, "API_KEY_REVOKED",
                "API_KEY", k.getId(), k.getName());
    }

    /**
     * Called from the auth filter on every successful authentication. Best-effort:
     * we swallow failures so a transient DB blip doesn't break auth.
     */
    @Transactional
    public void touch(UUID keyId, String ip) {
        try {
            ApiKey k = repo.findById(keyId).orElse(null);
            if (k == null) return;
            k.setLastUsedAt(Instant.now());
            k.setLastUsedIp(ip);
            repo.save(k);
        } catch (RuntimeException ex) {
            log.debug("touch() failed for key {}: {}", keyId, ex.getMessage());
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID orgId) {
        return repo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> findActiveByRawKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return Optional.empty();
        return repo.findByKeyHash(HashUtils.sha256(rawKey))
                .filter(ApiKey::isActive);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String generateRawKey() {
        byte[] buf = new byte[RAW_BYTES];
        RNG.nextBytes(buf);
        return PREFIX + "_" + HexFormat.of().formatHex(buf);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new BadRequestException("name too long (max 128)");
        }
        return trimmed;
    }

    private static Set<String> validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BadRequestException("at least one scope is required");
        }
        Set<String> valid = ApiKeyScope.allWireNames();
        for (String s : scopes) {
            if (!valid.contains(s)) {
                throw new BadRequestException("Unknown scope: " + s);
            }
        }
        return Set.copyOf(scopes);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
