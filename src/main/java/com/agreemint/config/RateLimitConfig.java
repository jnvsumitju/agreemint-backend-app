package com.agreemint.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires Bucket4j on top of the Lettuce Redis client the rest of the app already
 * uses (presence + collab). Two named buckets:
 *
 * <ul>
 *     <li><b>per-key</b> — a bucket keyed by API key id; capacity comes from the
 *         key row's {@code rate_limit_rpm}, refilling at that rate every minute.</li>
 *     <li><b>per-org</b> — a daily cap keyed by org id. Prevents any single org
 *         from accidentally spraying many millions of requests even if they
 *         spread them across many keys.</li>
 * </ul>
 *
 * <p>Bucket state lives in Redis so horizontal scaling of the backend doesn't
 * leak extra budget.
 */
@Configuration
public class RateLimitConfig {

    @Value("${agreemint.ratelimit.org-daily-max:10000}")
    private long orgDailyMax;

    // Reuses the same Redis connection coordinates the rest of the app already
    // talks to (presence + collab) — no new env vars needed. Mirrors the keys
    // Spring's Redis auto-config binds from REDIS_HOST / REDIS_PORT / REDIS_PASSWORD.
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient() {
        io.lettuce.core.RedisURI uri = io.lettuce.core.RedisURI.Builder
                .redis(redisHost, redisPort)
                .build();
        if (redisPassword != null && !redisPassword.isBlank()) {
            uri.setPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(uri);
    }

    @Bean
    public ProxyManager<String> rateLimitProxyManager(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return LettuceBasedProxyManager
                .builderFor(client.connect(codec))
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofDays(2)))
                .build();
    }

    /**
     * Bucket key suffix carrying the capacity the bucket was built for.
     *
     * <p>Bucket4j applies a {@link BucketConfiguration} only when a key is first
     * created; for an existing key the supplied config is ignored. Since these
     * buckets live for up to two days, a staff-changed limit would otherwise not
     * take effect until the old key expired — the portal would report the new
     * number while the old one kept being enforced.
     *
     * <p>Putting the capacity in the key sidesteps that: a changed limit is a
     * different key, so it gets a correctly-sized bucket immediately, with no
     * extra Redis round-trip on the request path. The trade is that a limit
     * change starts a fresh allowance rather than carrying consumption across —
     * and that changing back reuses the earlier bucket, consumption intact.
     */
    public static String capacitySuffix(long capacity) {
        return ":c" + capacity;
    }

    /** Per-minute bucket for a specific API key. Capacity = {@code rpm}, refill full every minute. */
    public static BucketConfiguration perKey(int rpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(Math.max(1, rpm))
                .refillGreedy(Math.max(1, rpm), Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /**
     * Per-IP bucket for the unauthenticated verification endpoint.
     *
     * <p>Needed because nothing else limits anonymous traffic:
     * {@code ApiKeyAuthenticationFilter} short-circuits when no {@code X-Api-Key}
     * header is present, so a {@code permitAll} route inherits no budget at all.
     *
     * <p>The endpoint answers "have you issued a file with this digest", so an
     * unbounded one is an oracle someone could grind against. Guessing a
     * SHA-256 is not a real threat, but paying for the database round trip is —
     * this is a cheap ceiling on that, not a security control in itself.
     */
    public static BucketConfiguration perIpVerify(int perMinute) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(Math.max(1, perMinute))
                .refillGreedy(Math.max(1, perMinute), Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /**
     * Per-IP bucket for the anonymous sandbox PDF download.
     *
     * <p>Sized per HOUR, not per minute, and deliberately small. Unlike
     * {@link #perIpVerify}, which guards a database lookup, this one guards an
     * iText render: synchronous, CPU-bound, and running on the same request
     * threads that serve paying customers. The limit is the real control here —
     * the "one free download" the UI promises is a client-side count that
     * incognito mode defeats in one keystroke, and it was never meant to be
     * more than a courtesy.
     *
     * <p>Keyed on a best-effort client address, which in India routinely means
     * a carrier-grade NAT shared by thousands of people. That is why the
     * default is a handful per hour rather than one: a cap of one would lock
     * out an entire office the moment a single colleague used it. Someone
     * determined to harvest all fifty templates still can — they are free and
     * public documents, so the only thing lost is CPU, which is exactly what
     * this bounds.
     */
    public static BucketConfiguration perIpAnonymousPdf(int perHour) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(Math.max(1, perHour))
                .refillGreedy(Math.max(1, perHour), Duration.ofHours(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /** Per-day bucket for an entire org, at the system-wide default. */
    public BucketConfiguration perOrgDaily() {
        return perOrgDaily(null);
    }

    /**
     * Per-day bucket for an org at a resolved cap.
     *
     * @param dailyMax the org's effective cap, or null to use the system
     *                 default. Resolved by {@code OrgEntitlementService} from
     *                 the org's plan and any staff override.
     */
    public BucketConfiguration perOrgDaily(Integer dailyMax) {
        long capacity = Math.max(1L, dailyMax == null ? orgDailyMax : dailyMax.longValue());
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofDays(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /**
     * Per-day bucket for an org's document generation, at an explicit cap.
     *
     * <p>Separate from {@link #perOrgDaily(Integer)} on purpose, and takes no
     * null: there is no system-wide PDF default to fall back to, so "no cap
     * configured" must be handled by the caller skipping the bucket entirely
     * rather than silently inheriting the API cap.
     */
    public static BucketConfiguration perOrgDailyPdf(int dailyMax) {
        long capacity = Math.max(1L, dailyMax);
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofDays(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /** The system-wide default, for callers that need to report it. */
    public long getOrgDailyMax() {
        return orgDailyMax;
    }
}
