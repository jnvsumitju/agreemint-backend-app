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

    /** Per-minute bucket for a specific API key. Capacity = {@code rpm}, refill full every minute. */
    public static BucketConfiguration perKey(int rpm) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(Math.max(1, rpm))
                .refillGreedy(Math.max(1, rpm), Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    /** Per-day bucket for an entire org. */
    public BucketConfiguration perOrgDaily() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(orgDailyMax)
                .refillGreedy(orgDailyMax, Duration.ofDays(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }
}
