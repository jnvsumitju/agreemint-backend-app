package com.agreemint.admin;

import com.agreemint.domain.ActivityLog;
import com.agreemint.repository.ActivityLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for the admin audit search.
 *
 * <p>The bug this guards against: filters used to be applied in memory
 * <em>after</em> a global "newest N" limit, so scoping a search to one org
 * usually returned nothing — the matching rows were never in the window. The
 * noisy-neighbour test below reproduces exactly that shape.
 */
@DataJpaTest
// Keep the datasource from application-test.yml; the default behaviour is to
// replace it with a stock embedded one, which loses NON_KEYWORDS and the
// JSONB alias the entities need.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuditSearchTest {

    @Autowired
    private ActivityLogRepository repo;

    private final UUID quietOrg = UUID.randomUUID();
    private final UUID noisyOrg = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private ActivityLog event(UUID orgId, UUID userId, String action, Instant at) {
        ActivityLog e = new ActivityLog();
        e.setOrgId(orgId);
        e.setUserId(userId);
        e.setUserName("user@example.com");
        e.setAction(action);
        e.setEntityType("Template");
        e.setEntityId(UUID.randomUUID());
        e.setEntityName("Some template");
        e.setCreatedAt(at);
        return e;
    }

    @BeforeEach
    void seed() {
        repo.deleteAll();
        Instant now = Instant.parse("2026-07-30T12:00:00Z");

        // One old event in the quiet org...
        repo.save(event(quietOrg, alice, "template.created", now.minusSeconds(10_000)));

        // ...buried under 200 newer events from a noisy neighbour.
        for (int i = 0; i < 200; i++) {
            repo.save(event(noisyOrg, bob, "document.generated", now.minusSeconds(i)));
        }
        repo.flush();
    }

    private Page<ActivityLog> search(UUID orgId, UUID userId, String action, int size) {
        return repo.search(orgId, userId, action,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Test
    void findsEventsHiddenBehindANoisyNeighbour() {
        // The regression: with in-memory filtering over the newest 100 rows,
        // this returned zero because all 100 belonged to the noisy org.
        Page<ActivityLog> result = search(quietOrg, null, null, 100);

        assertEquals(1, result.getTotalElements(), "the quiet org's event must be found");
        assertEquals(quietOrg, result.getContent().get(0).getOrgId());
    }

    @Test
    void noFiltersReturnsEverythingPaged() {
        Page<ActivityLog> result = search(null, null, null, 50);

        assertEquals(201, result.getTotalElements(), "total counts all rows, not just this page");
        assertEquals(50, result.getContent().size(), "page is capped at the requested size");
        assertEquals(5, result.getTotalPages());
    }

    @Test
    void filtersByUser() {
        assertEquals(1, search(null, alice, null, 100).getTotalElements());
        assertEquals(200, search(null, bob, null, 100).getTotalElements());
    }

    @Test
    void actionMatchesOnPrefixCaseInsensitively() {
        assertEquals(1, search(null, null, "template", 100).getTotalElements(),
                "a prefix should match template.created");
        assertEquals(1, search(null, null, "TEMPLATE.CREATED", 100).getTotalElements(),
                "an exact action should match regardless of case");
        assertEquals(0, search(null, null, "nonsense", 100).getTotalElements());
    }

    @Test
    void filtersCombine() {
        assertEquals(0, search(quietOrg, bob, null, 100).getTotalElements(),
                "bob has no events in the quiet org");
        assertEquals(1, search(quietOrg, alice, "template", 100).getTotalElements());
    }

    @Test
    void newestFirst() {
        Page<ActivityLog> result = search(noisyOrg, null, null, 10);
        Instant previous = null;
        for (ActivityLog e : result.getContent()) {
            if (previous != null) {
                assertFalse(e.getCreatedAt().isAfter(previous), "results must be newest-first");
            }
            previous = e.getCreatedAt();
        }
    }
}
