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
