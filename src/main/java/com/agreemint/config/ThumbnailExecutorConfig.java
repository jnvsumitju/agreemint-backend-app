package com.agreemint.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The pool that renders template preview images.
 *
 * <p>Its own executor rather than the application default, because there isn't
 * one. Boot's {@code applicationTaskExecutor} is
 * {@code @ConditionalOnMissingBean(Executor.class)} and
 * {@code @EnableWebSocketMessageBroker} already registers three Executor beans,
 * so it never gets created and every {@code @Async} method in this application
 * currently runs on a {@code SimpleAsyncTaskExecutor} — a brand-new unbounded
 * platform thread per call, with no pool, no queue and no backpressure.
 *
 * <p>That is survivable for the emails it is used for today. It is not
 * survivable for this: each thumbnail rasterises an A4 page at 150 DPI, which
 * is a 1240×1754 {@code BufferedImage} holding roughly 8 MB before the PNG
 * encoder allocates anything. Unbounded concurrency on that path turns a burst
 * of commits into an OutOfMemoryError that takes the whole application down —
 * to produce images that are, by design, disposable.
 *
 * <p>A plain {@link ThreadPoolExecutor} rather than Spring's
 * {@code ThreadPoolTaskExecutor}, purely for shutdown behaviour. The Spring
 * wrapper implements {@code SmartLifecycle}, and on context close it calls
 * {@code shutdown()} — which stops accepting work but keeps <em>draining the
 * queue</em> — then blocks the shutdown phase until the pool is idle or
 * {@code spring.lifecycle.timeout-per-shutdown-phase} (30s by default) expires.
 * A deploy landing while renders are queued would therefore stall for thirty
 * seconds, comfortably past Docker's ten-second SIGTERM grace, and the process
 * would be killed anyway. {@code destroyMethod = "shutdownNow"} discards the
 * backlog immediately instead, which is the right answer for images that the
 * next commit regenerates.
 */
@Configuration
public class ThumbnailExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailExecutorConfig.class);

    public static final String EXECUTOR = "thumbnailExecutor";

    @Bean(name = EXECUTOR, destroyMethod = "shutdownNow")
    public ThreadPoolExecutor thumbnailExecutor(
            @Value("${agreemint.thumbnails.pool-size:2}") int poolSize,
            @Value("${agreemint.thumbnails.queue-capacity:100}") int queueCapacity) {

        int threads = Math.max(1, poolSize);
        int queue = Math.max(1, queueCapacity);

        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                // Fixed size: core == max. A ThreadPoolExecutor only grows past
                // the core size once the queue is FULL, so a large queue with
                // max > core would never reach the extra threads. Two settings
                // that look like they cooperate and do not.
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue),
                new CustomizableThreadFactory("thumbnail-"),
                // Bounded queue plus a drop policy, not CallerRunsPolicy.
                // CallerRuns would execute the render on whichever thread
                // submitted it — after a commit that is a Tomcat request
                // thread, which is precisely the thread this exists to protect.
                (r, executor) -> log.warn(
                        "[thumbnail] Queue full ({} deep); dropping a render. The image stays "
                                + "stale until the next commit or editor capture. Raise "
                                + "agreemint.thumbnails.pool-size if this repeats.", queue));

        log.info("[thumbnail] Executor ready: {} thread(s), queue {}", threads, queue);
        return ex;
    }
}
