package com.agreemint.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed presence store. Data survives backend restart and can be shared
 * across multiple backend instances.
 *
 * Keys:
 *   presence:tmpl:{templateId}  — Hash: userId -> JSON UserPresence
 *   presence:user:{userId}      — Set of templateIds the user is currently viewing
 *
 * Both keys get a 10-minute TTL refreshed on every activity, so stale entries
 * self-expire if a client crashes without sending a disconnect.
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private static final String[] COLOR_PALETTE = {
            "#8B5CF6", "#EC4899", "#F59E0B", "#10B981",
            "#3B82F6", "#EF4444", "#6366F1", "#14B8A6"
    };

    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public PresenceService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static String tmplKey(UUID templateId) {
        return "presence:tmpl:" + templateId;
    }

    private static String userKey(UUID userId) {
        return "presence:user:" + userId;
    }

    public Set<UserPresence> join(UUID templateId, UUID userId, String name, String email) {
        String color = assignColor(userId);
        UserPresence presence = new UserPresence(
                userId, name, email, null, color, Instant.now()
        );

        try {
            String json = mapper.writeValueAsString(presence);
            String tKey = tmplKey(templateId);
            String uKey = userKey(userId);

            redis.opsForHash().put(tKey, userId.toString(), json);
            redis.opsForSet().add(uKey, templateId.toString());
            redis.expire(tKey, TTL);
            redis.expire(uKey, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize presence for userId={}", userId, e);
        }

        return getPresence(templateId);
    }

    public Set<UserPresence> leave(UUID templateId, UUID userId) {
        String tKey = tmplKey(templateId);
        String uKey = userKey(userId);
        redis.opsForHash().delete(tKey, userId.toString());
        redis.opsForSet().remove(uKey, templateId.toString());
        return getPresence(templateId);
    }

    public Set<UserPresence> getPresence(UUID templateId) {
        String tKey = tmplKey(templateId);
        Map<Object, Object> entries = redis.opsForHash().entries(tKey);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UserPresence> result = new LinkedHashSet<>();
        for (Object value : entries.values()) {
            if (value == null) continue;
            try {
                result.add(mapper.readValue(value.toString(), UserPresence.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse presence entry in {}: {}", tKey, e.getMessage());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Removes a user from all templates they're present in.
     * Returns the list of templateIds that were affected (for broadcasting).
     */
    public List<UUID> leaveAll(UUID userId) {
        String uKey = userKey(userId);
        Set<String> templateIds = redis.opsForSet().members(uKey);
        if (templateIds == null || templateIds.isEmpty()) {
            return List.of();
        }
        List<UUID> affected = new ArrayList<>(templateIds.size());
        Set<String> seen = new HashSet<>();
        for (String raw : templateIds) {
            if (raw == null || !seen.add(raw)) continue;
            UUID templateId;
            try {
                templateId = UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            redis.opsForHash().delete(tmplKey(templateId), userId.toString());
            affected.add(templateId);
        }
        redis.delete(uKey);
        return affected;
    }

    /** Deterministic color — same user always gets the same color across sessions. */
    private String assignColor(UUID userId) {
        int idx = Math.floorMod(userId.hashCode(), COLOR_PALETTE.length);
        return COLOR_PALETTE[idx];
    }

    public record UserPresence(
            UUID userId,
            String name,
            String email,
            String avatarUrl,
            String color,
            Instant connectedAt
    ) {
    }
}
