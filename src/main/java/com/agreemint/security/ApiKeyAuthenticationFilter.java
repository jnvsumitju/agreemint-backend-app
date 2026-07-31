package com.agreemint.security;

import com.agreemint.billing.OrgEntitlementService;
import com.agreemint.config.RateLimitConfig;
import com.agreemint.domain.ApiKey;
import com.agreemint.service.ApiKeyService;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Recognises {@code X-Api-Key} credentials and authenticates the request as the
 * owning org's service principal. Runs <em>before</em> {@link JwtAuthenticationFilter}
 * in the chain so customer-side cURLs never conflict with a stale browser JWT.
 *
 * <p>Responsibilities:
 * <ol>
 *     <li>Short-circuit (pass through) when header is absent.</li>
 *     <li>Hash + lookup the key; reject on revoked / expired.</li>
 *     <li>Enforce per-key IP allowlist if configured.</li>
 *     <li>Build a {@link UserPrincipal} carrying the key's org + scopes.</li>
 *     <li>Fire-and-forget {@code last_used_at / last_used_ip} update.</li>
 * </ol>
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;
    private final ProxyManager<String> rateLimitProxyManager;
    private final RateLimitConfig rateLimitConfig;
    private final OrgEntitlementService entitlements;

    public ApiKeyAuthenticationFilter(
            ApiKeyService apiKeyService,
            ProxyManager<String> rateLimitProxyManager,
            RateLimitConfig rateLimitConfig,
            OrgEntitlementService entitlements) {
        this.apiKeyService = apiKeyService;
        this.rateLimitProxyManager = rateLimitProxyManager;
        this.rateLimitConfig = rateLimitConfig;
        this.entitlements = entitlements;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiKey> maybe = apiKeyService.findActiveByRawKey(presented.trim());
        if (maybe.isEmpty()) {
            writeUnauthorized(response, "Invalid or expired API key");
            return;
        }
        ApiKey key = maybe.get();

        // IP allowlist enforcement.
        String ip = clientIp(request);
        if (key.getAllowedIps() != null && !key.getAllowedIps().isBlank()
                && !ipAllowed(ip, key.getAllowedIps())) {
            writeUnauthorized(response, "IP not allowed for this API key");
            return;
        }

        // Entitlement — the org's plan, staff overrides and freeze state.
        // Cached per instance for a minute, so this is not a DB hit per request.
        OrgEntitlementService.Entitlement entitlement = entitlements.resolve(key.getOrgId());

        // A frozen org is stopped before anything is consumed. 402 rather than
        // 403: this is a billing state the customer can resolve, not a
        // permissions problem.
        if (entitlement.frozen()) {
            writePaymentRequired(response, entitlement.frozenReason());
            return;
        }

        // Rate limiting — per-key (rpm), then per-org (daily).
        // The per-key rpm can be overridden for the whole org by staff.
        int effectiveRpm = entitlement.apiRpmOverride() != null
                ? entitlement.apiRpmOverride()
                : key.getRateLimitRpm();
        // Capacity is part of the key — see RateLimitConfig.capacitySuffix. Without
        // it a staff-changed limit would not reach an existing bucket for ~2 days.
        if (!consumeBucket(response,
                "apikey:" + key.getId() + RateLimitConfig.capacitySuffix(effectiveRpm),
                RateLimitConfig.perKey(effectiveRpm), "key")) {
            return;
        }
        long orgDailyCapacity = entitlement.apiDailyMax() != null
                ? entitlement.apiDailyMax()
                : rateLimitConfig.getOrgDailyMax();
        if (!consumeBucket(response,
                "org:" + key.getOrgId() + RateLimitConfig.capacitySuffix(orgDailyCapacity),
                rateLimitConfig.perOrgDaily(entitlement.apiDailyMax()), "org")) {
            return;
        }

        // Scope list → Spring authorities.
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String scope : key.scopeSet()) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }

        UserPrincipal principal = new UserPrincipal(
                key.getCreatedBy(),
                "api-key@" + key.getOrgId(),
                key.getOrgId(),
                null,
                key.scopeSet()
        );
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Best-effort — don't block the request on this write.
        try {
            apiKeyService.touch(key.getId(), ip);
        } catch (RuntimeException ex) {
            log.debug("Failed to touch last-used timestamp for key {}: {}", key.getId(), ex.getMessage());
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String clientIp(HttpServletRequest req) {
        // Respect X-Forwarded-For from the edge proxy (nginx / Cloudflare);
        // first hop wins. Fallback to direct peer address.
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    private static boolean ipAllowed(String clientIp, String cidrList) {
        if (clientIp == null || clientIp.isBlank()) return false;
        for (String raw : cidrList.split(",")) {
            String cidr = raw.trim();
            if (cidr.isEmpty()) continue;
            try {
                if (new IpAddressMatcher(cidr).matches(clientIp)) return true;
            } catch (IllegalArgumentException ex) {
                // Bad entry in the allowlist — skip it.
                log.warn("Skipping unparsable CIDR '{}': {}", cidr, ex.getMessage());
            }
        }
        return false;
    }

    private static void writeUnauthorized(HttpServletResponse resp, String reason) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + reason.replace("\"", "\\\"") + "\"}");
    }

    /**
     * 402 for a frozen org. The reason is staff-authored, so it is escaped
     * rather than trusted, and a generic fallback is used when none was given.
     */
    private static void writePaymentRequired(HttpServletResponse resp, String reason) throws IOException {
        String message = (reason == null || reason.isBlank())
                ? "This workspace is suspended. Please contact support."
                : reason;
        resp.setStatus(402);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
    }

    // Kept for future fail-closed time guards if clock drift becomes an issue.
    @SuppressWarnings("unused")
    private static boolean notExpired(Instant expiresAt) {
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    /**
     * Reserve one token from the named Bucket4j bucket. On success fills
     * {@code X-RateLimit-*} response headers; on failure writes 429 and returns
     * false (the caller should abort the chain).
     */
    private boolean consumeBucket(HttpServletResponse response, String bucketKey,
                                   io.github.bucket4j.BucketConfiguration config, String scope) {
        try {
            BucketProxy bucket = rateLimitProxyManager.builder().build(bucketKey, config);
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            response.setHeader("X-RateLimit-Scope-" + scope, "ok");
            response.setHeader("X-RateLimit-Remaining-" + scope,
                    Long.toString(Math.max(0, probe.getRemainingTokens())));
            if (!probe.isConsumed()) {
                long retryAfterSec = Math.max(1,
                        TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(retryAfterSec));
                response.setContentType("application/json");
                try {
                    response.getWriter().write(
                            "{\"error\":\"Rate limit exceeded for scope=" + scope + "\"}");
                } catch (IOException ignore) { /* client gone */ }
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            // Fail-open on infra errors — better to serve than to surface a confusing
            // 429 when the limiter backend is unhealthy. Logged at warn so we notice.
            log.warn("Rate limit check failed ({} bucket); allowing: {}", scope, ex.getMessage());
            return true;
        }
    }

    /** Reserved for future scoped-bucket variants (e.g. per-endpoint budget). */
    @SuppressWarnings("unused")
    private static String bucketName(UUID keyId, String endpoint) {
        return "apikey:" + keyId + ":" + endpoint;
    }
}
