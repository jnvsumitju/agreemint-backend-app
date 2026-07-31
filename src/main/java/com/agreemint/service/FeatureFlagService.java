package com.agreemint.service;

import com.agreemint.admin.domain.FeatureFlag;
import com.agreemint.admin.domain.FeatureFlagOverride;
import com.agreemint.admin.repository.FeatureFlagOverrideRepository;
import com.agreemint.admin.repository.FeatureFlagRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves feature flags for an organisation.
 *
 * <p>Until this existed, {@code feature_flags} and {@code feature_flag_overrides}
 * were written by the admin API and read by nothing — toggling a flag changed
 * no behaviour anywhere in the product.
 *
 * <p>Resolution, most specific first:
 * <ol>
 *   <li>a per-org row in {@code feature_flag_overrides}</li>
 *   <li>the flag's {@code defaultEnabled}</li>
 *   <li><strong>false</strong> — an unknown key is off</li>
 * </ol>
 *
 * <p>That last rule is deliberate: a typo in a flag name should disable a
 * feature, not silently enable it for everyone.
 *
 * <p>Cached per org for a short window, mirroring {@code OrgEntitlementService}.
 * The admin controller invalidates on every write, so a staff toggle is visible
 * immediately on the node that served it and within {@link #TTL} everywhere else.
 */
@Service
public class FeatureFlagService {

    private static final Duration TTL = Duration.ofSeconds(60);

    private record CacheEntry(Map<String, Boolean> flags, Instant expiresAt) {}

    private final FeatureFlagRepository flagRepo;
    private final FeatureFlagOverrideRepository overrideRepo;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Key used for the "no organisation" resolution, e.g. an anonymous caller. */
    private static final UUID NO_ORG = new UUID(0L, 0L);

    public FeatureFlagService(FeatureFlagRepository flagRepo,
                               FeatureFlagOverrideRepository overrideRepo) {
        this.flagRepo = flagRepo;
        this.overrideRepo = overrideRepo;
    }

    /** Whether {@code key} is on for this org. Unknown keys are off. */
    public boolean isEnabled(String key, UUID orgId) {
        if (key == null || key.isBlank()) return false;
        return resolveAll(orgId).getOrDefault(key, false);
    }

    /** Every known flag, resolved for this org. */
    public Map<String, Boolean> resolveAll(UUID orgId) {
        UUID cacheKey = orgId == null ? NO_ORG : orgId;

        CacheEntry cached = cache.get(cacheKey);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.flags();
        }

        Map<String, Boolean> resolved = load(orgId);
        cache.put(cacheKey, new CacheEntry(resolved, now.plus(TTL)));
        return resolved;
    }

    /** Drop the whole cache — used when a flag's default or definition changes. */
    public void invalidateAll() {
        cache.clear();
    }

    /** Drop one org's cache — used when only its override changed. */
    public void invalidateOrg(UUID orgId) {
        if (orgId != null) cache.remove(orgId);
    }

    private Map<String, Boolean> load(UUID orgId) {
        Map<String, Boolean> resolved = new HashMap<>();
        for (FeatureFlag flag : flagRepo.findAll()) {
            resolved.put(flag.getKey(), flag.isDefaultEnabled());
        }

        if (orgId != null) {
            for (FeatureFlagOverride override : overrideRepo.findByOrgId(orgId)) {
                // Only override flags that still exist — a stale override for a
                // deleted flag must not resurrect it.
                if (resolved.containsKey(override.getFlagKey())) {
                    resolved.put(override.getFlagKey(), override.isEnabled());
                }
            }
        }
        return Map.copyOf(resolved);
    }
}
