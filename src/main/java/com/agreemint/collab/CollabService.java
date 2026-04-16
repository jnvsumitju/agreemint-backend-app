package com.agreemint.collab;

import com.agreemint.domain.TemplateDraft;
import com.agreemint.repository.TemplateDraftRepository;
import com.agreemint.service.TemplateDraftService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis-backed hot layout for the collaborative editor.
 *
 * <p>The "live" layout for a template stays in Redis while any editor is connected.
 * Ops applied through this service advance a monotonic seq (for client reconciliation),
 * mutate the hot layout, and mark it dirty for the periodic flush job to persist.
 *
 * <p>Keys (all with 10-minute TTL, refreshed on every touch):
 * <ul>
 *     <li>{@code template:live:{id}:layout} — STRING, full structural layout JSON</li>
 *     <li>{@code template:live:{id}:seq} — STRING, monotonic op counter</li>
 *     <li>{@code template:live:{id}:dirty} — STRING "1" when a flush is needed</li>
 * </ul>
 *
 * <p>Per-template serialisation uses an in-process {@link ReentrantLock}. That is correct
 * for single-backend deployments (the STOMP MessageMapping dispatcher is multi-threaded
 * but all ops for a given template go through this JVM). If this app is ever scaled
 * horizontally, swap the lock for a Redis SETNX lock.
 */
@Service
public class CollabService {

    private static final Logger log = LoggerFactory.getLogger(CollabService.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SimpMessagingTemplate messaging;
    private final TemplateDraftRepository draftRepo;
    private final TemplateDraftService draftService;

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CollabService(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            SimpMessagingTemplate messaging,
            TemplateDraftRepository draftRepo,
            TemplateDraftService draftService) {
        this.redis = redis;
        this.mapper = mapper;
        this.messaging = messaging;
        this.draftRepo = draftRepo;
        this.draftService = draftService;
    }

    // ── keys ─────────────────────────────────────────────────────────────────────

    private static String layoutKey(UUID templateId) {
        return "template:live:" + templateId + ":layout";
    }

    private static String seqKey(UUID templateId) {
        return "template:live:" + templateId + ":seq";
    }

    private static String dirtyKey(UUID templateId) {
        return "template:live:" + templateId + ":dirty";
    }

    private static final String DIRTY_INDEX = "template:live:dirty-index";

    // ── public API ───────────────────────────────────────────────────────────────

    /**
     * Returns the current hot layout, hydrating from Postgres if Redis is cold.
     * Always returns a non-null JsonNode (empty layout if no draft exists).
     */
    public Snapshot snapshot(UUID templateId) {
        ReentrantLock lock = lockFor(templateId);
        lock.lock();
        try {
            JsonNode layout = readLayout(templateId);
            if (layout == null) {
                layout = hydrateFromPostgres(templateId);
                writeLayout(templateId, layout);
            }
            long seq = currentSeq(templateId);
            touch(templateId);
            return new Snapshot(layout, seq);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies an op to the hot layout, advances seq, marks dirty, and broadcasts.
     * Returns the assigned serverSeq.
     */
    public long applyOp(UUID templateId, CollabOp op, UUID userId, String clientOpId) {
        ReentrantLock lock = lockFor(templateId);
        lock.lock();
        try {
            JsonNode layout = readLayout(templateId);
            if (layout == null) {
                layout = hydrateFromPostgres(templateId);
            }
            ObjectNode root;
            if (layout instanceof ObjectNode obj) {
                root = obj;
            } else {
                log.warn("Layout for template {} is not an object; replacing with empty", templateId);
                root = emptyLayout();
            }
            applyTo(root, op);
            writeLayout(templateId, root);
            long serverSeq = incrementSeq(templateId);
            markDirty(templateId);
            touch(templateId);

            Map<String, Object> broadcast = Map.of(
                    "serverSeq", serverSeq,
                    "clientOpId", clientOpId == null ? "" : clientOpId,
                    "userId", userId == null ? "" : userId.toString(),
                    "op", op
            );
            messaging.convertAndSend("/topic/template/" + templateId + "/ops", broadcast);
            return serverSeq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flushes the current hot layout to Postgres if dirty. Safe to call concurrently
     * with ops — the read + write is serialised on the per-template lock.
     */
    public boolean flushIfDirty(UUID templateId) {
        ReentrantLock lock = lockFor(templateId);
        lock.lock();
        try {
            String dirty = redis.opsForValue().get(dirtyKey(templateId));
            if (!"1".equals(dirty)) {
                return false;
            }
            JsonNode layout = readLayout(templateId);
            if (layout == null) {
                // Nothing to flush — clear the dirty flag and move on.
                redis.delete(dirtyKey(templateId));
                redis.opsForSet().remove(DIRTY_INDEX, templateId.toString());
                return false;
            }
            draftService.saveFromCollabFlush(templateId, layout);
            redis.delete(dirtyKey(templateId));
            redis.opsForSet().remove(DIRTY_INDEX, templateId.toString());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Flush failed for template {}: {}", templateId, ex.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the set of templateIds that currently need a flush. */
    public Set<UUID> dirtyTemplates() {
        Set<String> members = redis.opsForSet().members(DIRTY_INDEX);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        for (String m : members) {
            try {
                result.add(UUID.fromString(m));
            } catch (IllegalArgumentException ignore) { /* skip */ }
        }
        return result;
    }

    /**
     * Drops the hot keys for a template. Intended for when the last editor leaves —
     * the flush has already persisted the state; the TTL would clean this up anyway,
     * but explicit cleanup makes tests/observability cleaner.
     */
    public void evict(UUID templateId) {
        ReentrantLock lock = lockFor(templateId);
        lock.lock();
        try {
            redis.delete(List.of(layoutKey(templateId), seqKey(templateId), dirtyKey(templateId)));
            redis.opsForSet().remove(DIRTY_INDEX, templateId.toString());
        } finally {
            lock.unlock();
            locks.remove(templateId);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private ReentrantLock lockFor(UUID templateId) {
        return locks.computeIfAbsent(templateId, k -> new ReentrantLock());
    }

    private JsonNode readLayout(UUID templateId) {
        String raw = redis.opsForValue().get(layoutKey(templateId));
        if (raw == null) return null;
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            log.warn("Corrupt hot layout for {}; discarding", templateId);
            redis.delete(layoutKey(templateId));
            return null;
        }
    }

    private void writeLayout(UUID templateId, JsonNode layout) {
        try {
            redis.opsForValue().set(layoutKey(templateId), mapper.writeValueAsString(layout), TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise layout for {}", templateId, e);
        }
    }

    private long currentSeq(UUID templateId) {
        String s = redis.opsForValue().get(seqKey(templateId));
        if (s == null) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long incrementSeq(UUID templateId) {
        Long next = redis.opsForValue().increment(seqKey(templateId));
        redis.expire(seqKey(templateId), TTL);
        return next == null ? 0L : next;
    }

    private void markDirty(UUID templateId) {
        redis.opsForValue().set(dirtyKey(templateId), "1", TTL);
        redis.opsForSet().add(DIRTY_INDEX, templateId.toString());
    }

    private void touch(UUID templateId) {
        redis.expire(layoutKey(templateId), TTL);
        redis.expire(seqKey(templateId), TTL);
    }

    private JsonNode hydrateFromPostgres(UUID templateId) {
        return draftRepo.findById(templateId)
                .map(TemplateDraft::getLayoutJson)
                .filter(j -> j != null && !j.isNull())
                .orElseGet(this::emptyLayout);
    }

    private ObjectNode emptyLayout() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("pages", JsonNodeFactory.instance.arrayNode());
        root.set("globalVariables", JsonNodeFactory.instance.arrayNode());
        return root;
    }

    // ── op application ───────────────────────────────────────────────────────────

    private void applyTo(ObjectNode root, CollabOp op) {
        if (op instanceof CollabOp.AddElement e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null) return;
            arrayField(page, "elements").add(e.element() == null ? JsonNodeFactory.instance.objectNode() : e.element());

        } else if (op instanceof CollabOp.DeleteElements e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null) return;
            ArrayNode elements = arrayField(page, "elements");
            if (e.elementIds() == null || e.elementIds().isEmpty()) return;
            Set<String> toRemove = new HashSet<>(e.elementIds());
            ArrayNode kept = JsonNodeFactory.instance.arrayNode();
            for (JsonNode el : elements) {
                String id = el.path("id").asText(null);
                if (id == null || !toRemove.contains(id)) kept.add(el);
            }
            page.set("elements", kept);

        } else if (op instanceof CollabOp.UpdateElement e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null) return;
            ObjectNode el = findElementById(arrayField(page, "elements"), e.elementId());
            if (el != null && e.patch() != null) {
                deepMerge(el, e.patch());
            }

        } else if (op instanceof CollabOp.BulkUpdateElements e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null || e.updates() == null) return;
            ArrayNode elements = arrayField(page, "elements");
            for (CollabOp.BulkUpdateElements.ElementPatch u : e.updates()) {
                ObjectNode el = findElementById(elements, u.elementId());
                if (el != null && u.patch() != null) deepMerge(el, u.patch());
            }

        } else if (op instanceof CollabOp.AddPage e) {
            ArrayNode pages = arrayField(root, "pages");
            int idx = Math.max(0, Math.min(e.index(), pages.size()));
            JsonNode page = e.page() == null ? newEmptyPage() : e.page();
            // Jackson ArrayNode.insert preserves other entries.
            pages.insert(idx, page);

        } else if (op instanceof CollabOp.DeletePage e) {
            ArrayNode pages = arrayField(root, "pages");
            if (e.index() >= 0 && e.index() < pages.size()) {
                pages.remove(e.index());
            }

        } else if (op instanceof CollabOp.ReorderPages e) {
            ArrayNode pages = arrayField(root, "pages");
            int from = e.from(), to = e.to();
            if (from < 0 || from >= pages.size() || to < 0 || to >= pages.size() || from == to) return;
            JsonNode moved = pages.remove(from);
            pages.insert(Math.min(to, pages.size()), moved);

        } else if (op instanceof CollabOp.UpdatePage e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null || e.patch() == null) return;
            // The patch may include any page-level field (pageSpec, name, guides, …) except elements.
            // If it does include elements, we still allow it — caller is responsible.
            deepMerge(page, e.patch());

        } else if (op instanceof CollabOp.SetGlobalVariables e) {
            root.set("globalVariables", e.variables() == null ? JsonNodeFactory.instance.arrayNode() : e.variables());

        } else if (op instanceof CollabOp.SetPageVariables e) {
            ObjectNode page = pageAt(root, e.pageIndex());
            if (page == null) return;
            page.set("localVariables", e.variables() == null ? JsonNodeFactory.instance.arrayNode() : e.variables());
        }
    }

    private static ObjectNode pageAt(ObjectNode root, int index) {
        JsonNode pages = root.get("pages");
        if (!(pages instanceof ArrayNode arr)) return null;
        if (index < 0 || index >= arr.size()) return null;
        JsonNode page = arr.get(index);
        return (page instanceof ObjectNode o) ? o : null;
    }

    private static ArrayNode arrayField(ObjectNode parent, String name) {
        JsonNode cur = parent.get(name);
        if (cur instanceof ArrayNode arr) return arr;
        ArrayNode fresh = JsonNodeFactory.instance.arrayNode();
        parent.set(name, fresh);
        return fresh;
    }

    private static ObjectNode findElementById(ArrayNode elements, String elementId) {
        if (elementId == null) return null;
        for (JsonNode el : elements) {
            if (el instanceof ObjectNode o && elementId.equals(o.path("id").asText(null))) {
                return o;
            }
        }
        return null;
    }

    /**
     * Patch-style merge: for each key in {@code patch}, if both sides are objects recurse,
     * otherwise replace. Arrays and primitives are replaced wholesale — callers that want
     * array merges must send the full new array. Keys set to {@code null} delete the target.
     */
    private static void deepMerge(ObjectNode target, JsonNode patch) {
        if (!(patch instanceof ObjectNode p)) return;
        p.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                target.remove(key);
                return;
            }
            JsonNode existing = target.get(key);
            if (existing instanceof ObjectNode existingObj && value instanceof ObjectNode newObj) {
                deepMerge(existingObj, newObj);
            } else {
                target.set(key, value);
            }
        });
    }

    private static ObjectNode newEmptyPage() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.put("id", UUID.randomUUID().toString());
        page.set("elements", JsonNodeFactory.instance.arrayNode());
        return page;
    }

    // ── DTO ──────────────────────────────────────────────────────────────────────

    public record Snapshot(JsonNode layout, long seq) {}
}
