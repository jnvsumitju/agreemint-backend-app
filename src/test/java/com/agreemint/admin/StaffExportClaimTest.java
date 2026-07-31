package com.agreemint.admin;

import com.agreemint.admin.domain.StaffExport;
import com.agreemint.admin.repository.StaffExportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the claim that made staff exports work at all.
 *
 * <p>{@code claim()} is a {@code @Modifying @Query}, and Spring Data does not
 * apply the repository's class-level transaction to custom query methods. The
 * scheduler thread has no ambient transaction either, so without an explicit
 * {@code @Transactional} on the method this threw
 * {@code TransactionRequiredException} on every poll and no export ever left
 * PENDING — the feature had never completed once since it was written.
 *
 * <p>{@code NOT_SUPPORTED} is the whole point of this test: {@code @DataJpaTest}
 * normally wraps each test in a transaction, which would paper over exactly the
 * bug being guarded against. This runs the way the scheduler actually runs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StaffExportClaimTest {

    @Autowired private StaffExportRepository exportRepo;

    private StaffExport pending() {
        StaffExport e = new StaffExport();
        e.setId(UUID.randomUUID());
        e.setScope("audit");
        e.setStatus(StaffExport.Status.PENDING.name());
        e.setRequestedBy(UUID.randomUUID());
        e.setRequestedAt(Instant.now());
        return exportRepo.save(e);
    }

    @Test
    void claimSucceedsWithoutAnAmbientTransaction() {
        StaffExport e = pending();

        int claimed = exportRepo.claim(e.getId(),
                StaffExport.Status.PENDING.name(), StaffExport.Status.PROCESSING.name());

        assertEquals(1, claimed, "the scheduler runs with no transaction; claim must carry its own");
        assertEquals(StaffExport.Status.PROCESSING.name(),
                exportRepo.findById(e.getId()).orElseThrow().getStatus());
    }

    @Test
    void aSecondClaimLosesTheRace() {
        StaffExport e = pending();

        assertEquals(1, exportRepo.claim(e.getId(), "PENDING", "PROCESSING"));
        // The CAS on status is what stops two instances processing one export.
        assertEquals(0, exportRepo.claim(e.getId(), "PENDING", "PROCESSING"),
                "an already-claimed export must not be claimable again");
    }

    @Test
    void theClaimIsCommittedNotJustVisibleToTheCaller() {
        StaffExport e = pending();
        exportRepo.claim(e.getId(), "PENDING", "PROCESSING");

        // With no surrounding transaction there is nothing to roll back into, so
        // reading it back fresh proves the write actually landed — the property
        // the cross-instance guarantee depends on.
        List<StaffExport> stillPending =
                exportRepo.findTop10ByStatusOrderByRequestedAtAsc(StaffExport.Status.PENDING.name());
        assertTrue(stillPending.stream().noneMatch(x -> x.getId().equals(e.getId())));
    }
}
