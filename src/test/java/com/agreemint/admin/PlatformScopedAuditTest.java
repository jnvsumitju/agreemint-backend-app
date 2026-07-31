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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for platform-wide staff actions in the audit log.
 *
 * <p>A {@code scope=audit} staff export reads every tenant's activity, so it
 * belongs to no single org. {@code activity_log.org_id} was NOT NULL, which
 * meant the single broadest action staff can take was the one the audit view
 * could not record at all — it produced a WARN line and nothing else. V22
 * relaxed the column.
 *
 * <p>The load-bearing property is that relaxing it did not open a leak: a row
 * with no org must be visible to staff and invisible to every tenant.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PlatformScopedAuditTest {

    @Autowired private ActivityLogRepository auditRepo;

    private final UUID orgA = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();

    private ActivityLog row(UUID orgId, String action) {
        ActivityLog a = new ActivityLog();
        a.setOrgId(orgId);
        a.setUserId(staffId);
        a.setUserName("staff@crixaa.com");
        a.setAction(action);
        a.setEntityType("StaffExport");
        a.setEntityId(UUID.randomUUID());
        a.setEntityName("audit");
        return auditRepo.save(a);
    }

    @BeforeEach
    void seed() {
        auditRepo.deleteAll();
    }

    @Test
    void anOrgLessRowCanBePersisted() {
        ActivityLog saved = row(null, "export.request");

        // The whole point: this used to violate a NOT NULL constraint, so the
        // action went unrecorded.
        assertNotNull(saved.getId());
        assertNull(auditRepo.findById(saved.getId()).orElseThrow().getOrgId());
    }

    @Test
    void staffSeeItInTheUnfilteredAuditView() {
        row(null, "export.request");
        row(orgA, "template.created");

        Page<ActivityLog> all = auditRepo.search(null, null, "",
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(2, all.getTotalElements());
        assertTrue(all.getContent().stream().anyMatch(a -> a.getOrgId() == null),
                "a platform-wide action must appear in the staff audit view");
    }

    @Test
    void noTenantCanSeeIt() {
        row(null, "export.request");
        row(orgA, "template.created");

        // The customer feed always queries a concrete org id, and NULL never
        // equals one. This is what makes relaxing the column safe.
        List<ActivityLog> feed =
                auditRepo.findByOrgIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 50));

        assertEquals(1, feed.size());
        assertEquals("template.created", feed.get(0).getAction());
        assertTrue(feed.stream().noneMatch(a -> a.getOrgId() == null));
    }

    @Test
    void anOrgScopedStaffSearchExcludesIt() {
        row(null, "export.request");
        row(orgA, "template.created");

        Page<ActivityLog> scoped = auditRepo.search(orgA, null, "",
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        // Reviewing one org should not surface actions that were not about it.
        assertEquals(1, scoped.getTotalElements());
        assertEquals(orgA, scoped.getContent().get(0).getOrgId());
    }
}
