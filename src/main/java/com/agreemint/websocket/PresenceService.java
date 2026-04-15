package com.agreemint.websocket;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PresenceService {

    private static final String[] COLOR_PALETTE = {
            "#8B5CF6", "#EC4899", "#F59E0B", "#10B981",
            "#3B82F6", "#EF4444", "#6366F1", "#14B8A6"
    };

    private final ConcurrentHashMap<UUID, Set<UserPresence>> presenceMap = new ConcurrentHashMap<>();

    public Set<UserPresence> join(UUID templateId, UUID userId, String name, String email) {
        Set<UserPresence> users = presenceMap.computeIfAbsent(templateId,
                k -> ConcurrentHashMap.newKeySet());

        // Remove existing entry for this user (reconnect scenario)
        users.removeIf(p -> p.userId().equals(userId));

        String color = assignColor(users);
        UserPresence presence = new UserPresence(
                userId, name, email, null, color, Instant.now()
        );
        users.add(presence);

        return Collections.unmodifiableSet(users);
    }

    public Set<UserPresence> leave(UUID templateId, UUID userId) {
        Set<UserPresence> users = presenceMap.get(templateId);
        if (users == null) {
            return Collections.emptySet();
        }
        users.removeIf(p -> p.userId().equals(userId));
        if (users.isEmpty()) {
            presenceMap.remove(templateId);
        }
        return Collections.unmodifiableSet(users);
    }

    public Set<UserPresence> getPresence(UUID templateId) {
        Set<UserPresence> users = presenceMap.get(templateId);
        if (users == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(users);
    }

    /**
     * Removes a user from all templates they're present in.
     * Returns the list of templateIds that were affected.
     */
    public List<UUID> leaveAll(UUID userId) {
        List<UUID> affected = new ArrayList<>();
        Iterator<Map.Entry<UUID, Set<UserPresence>>> it = presenceMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Set<UserPresence>> entry = it.next();
            boolean removed = entry.getValue().removeIf(p -> p.userId().equals(userId));
            if (removed) {
                affected.add(entry.getKey());
                if (entry.getValue().isEmpty()) {
                    it.remove();
                }
            }
        }
        return affected;
    }

    private String assignColor(Set<UserPresence> existing) {
        Set<String> usedColors = ConcurrentHashMap.newKeySet();
        for (UserPresence p : existing) {
            usedColors.add(p.color());
        }
        for (String color : COLOR_PALETTE) {
            if (!usedColors.contains(color)) {
                return color;
            }
        }
        // All colors used — cycle based on count
        return COLOR_PALETTE[existing.size() % COLOR_PALETTE.length];
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
