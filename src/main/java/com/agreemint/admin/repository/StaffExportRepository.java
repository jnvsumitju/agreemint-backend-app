package com.agreemint.admin.repository;

import com.agreemint.admin.domain.StaffExport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StaffExportRepository extends JpaRepository<StaffExport, UUID> {
    List<StaffExport> findTop50ByOrderByRequestedAtDesc();

    /** Oldest first, so the queue is fair rather than LIFO. */
    List<StaffExport> findTop10ByStatusOrderByRequestedAtAsc(String status);

    /**
     * Atomically move one row between statuses.
     *
     * <p>Returns 1 if this caller won the row and 0 if another instance got
     * there first. Claiming with a conditional update rather than a read-then-
     * write means two app instances polling the same queue cannot both process
     * the same export.
     */
    /**
     * Claim one export by CAS on status.
     *
     * <p>{@code @Transactional} is load-bearing, not decoration. The scheduler
     * thread has no ambient transaction and Spring Data does NOT apply the
     * repository's class-level transaction to custom {@code @Query} methods, so
     * without it this threw {@code TransactionRequiredException} on every poll
     * and no export ever left PENDING — the exact failure the worker was
     * written to fix. Its own short transaction also commits the claim
     * immediately, which is what makes the CAS meaningful across instances;
     * putting the annotation on the caller instead would hold the claim open
     * across the R2 upload.
     */
    @Transactional
    @Modifying
    @Query("UPDATE StaffExport e SET e.status = :to WHERE e.id = :id AND e.status = :from")
    int claim(@Param("id") UUID id, @Param("from") String from, @Param("to") String to);
}
