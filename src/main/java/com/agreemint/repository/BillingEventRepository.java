package com.agreemint.repository;

import com.agreemint.domain.BillingEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BillingEventRepository extends JpaRepository<BillingEvent, UUID> {

    boolean existsByRazorpayEventId(String razorpayEventId);

    /** Payment history for the billing tab — charge events carry the amount. */
    List<BillingEvent> findByOrgIdAndEventTypeOrderByCreatedAtDesc(
            UUID orgId, String eventType, Pageable pageable);
}
