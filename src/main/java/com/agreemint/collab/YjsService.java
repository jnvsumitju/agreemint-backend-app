package com.agreemint.collab;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Backend-side Yjs relay.
 *
 * <p>Yjs CRDT state is an opaque binary document on the client. The backend is a
 * pure relay: it broadcasts updates between connected clients and persists the
 * latest client-provided snapshot in Redis so a late joiner can catch up.
 *
 * <p>Keys (all with 10-minute TTL, refreshed on every touch):
 * <ul>
 *     <li>{@code template:yjs:{id}:state} — latest base64-encoded Y.Doc snapshot</li>
 *     <li>{@code template:yjs:{id}:updates} — list of base64 updates since last snapshot (LTRIM-capped)</li>
 * </ul>
 *
 * <p>Client-driven compaction: one of the connected clients periodically posts a
 * fresh snapshot which replaces the state and empties the updates list. The server
 * never parses the binary — it does not depend on Yjs at runtime.
 */
@Service
public class YjsService {

    private static final Duration TTL = Duration.ofMinutes(10);
    /** Hard cap on buffered updates before we drop from the head. */
    private static final long UPDATES_MAX = 200;

    private final StringRedisTemplate redis;

    public YjsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String stateKey(UUID templateId) {
        return "template:yjs:" + templateId + ":state";
    }

    private static String updatesKey(UUID templateId) {
        return "template:yjs:" + templateId + ":updates";
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Append a client update (base64) to the buffered list, LTRIM to the cap, refresh TTL. */
    public void appendUpdate(UUID templateId, String base64Update) {
        if (base64Update == null || base64Update.isEmpty()) return;
        String key = updatesKey(templateId);
        redis.opsForList().rightPush(key, base64Update);
        redis.opsForList().trim(key, -UPDATES_MAX, -1);
        redis.expire(key, TTL);
        // Also refresh state TTL so the whole doc ages out together.
        if (Boolean.TRUE.equals(redis.hasKey(stateKey(templateId)))) {
            redis.expire(stateKey(templateId), TTL);
        }
    }

    /** Replace the snapshot with a new client-provided encoding; clear buffered updates. */
    public void replaceSnapshot(UUID templateId, String base64State) {
        if (base64State == null) return;
        redis.opsForValue().set(stateKey(templateId), base64State, TTL);
        redis.delete(updatesKey(templateId));
    }

    /** Current state as returned to a new joiner. */
    public State hydrate(UUID templateId) {
        String state = redis.opsForValue().get(stateKey(templateId));
        List<String> updates = redis.opsForList().range(updatesKey(templateId), 0, -1);
        if (updates == null) updates = Collections.emptyList();
        // Refresh TTLs on any activity
        if (state != null) redis.expire(stateKey(templateId), TTL);
        if (!updates.isEmpty()) redis.expire(updatesKey(templateId), TTL);
        return new State(state, updates);
    }

    /** Drop Redis keys for a template. Called when the last editor leaves. */
    public void evict(UUID templateId) {
        redis.delete(List.of(stateKey(templateId), updatesKey(templateId)));
    }

    public record State(String state, List<String> updates) {}
}
