package com.agreemint.repository;

import com.agreemint.domain.Subscription;
import com.agreemint.domain.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /**
     * The org's live subscription, if any. At most one row can match — the
     * partial unique index in V20 enforces it — so returning Optional is safe.
     */
    Optional<Subscription> findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(
            UUID orgId, List<SubscriptionStatus> statuses);

    List<Subscription> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
