package com.agreemint.admin.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Registry of live impersonation sessions.
 *
 * <p>Impersonation tokens are stateless JWTs, so without this there is no way
 * to end a session early — it simply runs to its expiry, and the only remedy
 * for a mistake is to wait. This adds a Redis key per session that the auth
 * filter checks, so a session can be cut off the moment it is revoked.
 *
 * <p>The stored value is a small JSON record rather than just the operator id.
 * With only an id, nothing could <em>list</em> live sessions, so the revoke
 * endpoint was reachable only by whoever still had the session id in front of
 * them — which is the one person least likely to want to end it. Recording who
 * is impersonating whom, in which org, makes the session list possible and
 * gives the {@code impersonate.end} audit event something to name.
 *
 * <p><strong>Fails closed.</strong> If Redis is unreachable the session is
 * treated as not live and the token is rejected. That does mean a Redis blip
 * ends active support sessions — the right trade for a short-lived,
 * fully-privileged credential, where the recovery is simply to start another.
 */
@Service
public class ImpersonationSessionService {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationSessionService.class);
    private static final String PREFIX = "impersonation:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ImpersonationSessionService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * A live session.
     *
     * @param secondsRemaining filled from the Redis TTL on read, never stored —
     *                         it is the only value that stays honest after a
     *                         revoke or an early expiry
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
            String sessionId,
            UUID operatorId,
            String operatorEmail,
            UUID targetUserId,
            String targetEmail,
            UUID orgId,
            String orgName,
            Instant startedAt,
            Long secondsRemaining
    ) {}

    private static String key(String sessionId) {
        return PREFIX + sessionId;
    }

    /**
     * Record a session as live for {@code ttl}.
     *
     * <p>Throws rather than swallowing a Redis failure: the auth filter rejects
     * any impersonation token whose session is not live, so a silent failure
     * here would hand the operator a credential that cannot work.
     */
    public void register(Session session, Duration ttl) {
        try {
            redis.opsForValue().set(key(session.sessionId()), mapper.writeValueAsString(session), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not register impersonation session", e);
        }
    }

    /** Whether the session is still live. False when Redis cannot be reached. */
    public boolean isLive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(sessionId)));
        } catch (RuntimeException e) {
            log.error("Could not check impersonation session {} — rejecting", sessionId, e);
            return false;
        }
    }

    /** The full record with a live {@code secondsRemaining}, or null if not live. */
    public Session find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            String raw = redis.opsForValue().get(key(sessionId));
            if (raw == null) return null;
            Session s = mapper.readValue(raw, Session.class);
            Long ttl = redis.getExpire(key(sessionId), TimeUnit.SECONDS);
            return new Session(s.sessionId(), s.operatorId(), s.operatorEmail(),
                    s.targetUserId(), s.targetEmail(), s.orgId(), s.orgName(), s.startedAt(),
                    ttl != null && ttl >= 0 ? ttl : null);
        } catch (Exception e) {
            log.warn("Could not read impersonation session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** The staff user who started the session, or null if it is not live. */
    public UUID operatorOf(String sessionId) {
        Session s = find(sessionId);
        return s == null ? null : s.operatorId();
    }

    /**
     * Every live session, newest first.
     *
     * <p>SCAN rather than KEYS: this shares a Redis instance with the rate
     * limiter and collab presence, and KEYS blocks the server for the whole
     * sweep.
     */
    public List<Session> listLive() {
        List<Session> out = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                Session s = find(cursor.next().substring(PREFIX.length()));
                if (s != null) out.add(s);
            }
        } catch (RuntimeException e) {
            log.error("Could not list impersonation sessions", e);
        }
        out.sort(Comparator.comparing(Session::startedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * End a session immediately.
     *
     * @return the session that was ended, or null if it was not live — the
     *         caller needs its details to write the audit event
     */
    public Session revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session existing = find(sessionId);
            if (existing == null) return null;
            return Boolean.TRUE.equals(redis.delete(key(sessionId))) ? existing : null;
        } catch (RuntimeException e) {
            log.error("Could not revoke impersonation session {}", sessionId, e);
            return null;
        }
    }
}
