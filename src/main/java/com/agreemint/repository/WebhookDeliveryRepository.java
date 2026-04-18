package com.agreemint.repository;

import com.agreemint.domain.WebhookDelivery;
import com.agreemint.domain.WebhookDelivery.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /** Pending deliveries whose retry window has arrived. Caller paginates by a small LIMIT. */
    List<WebhookDelivery> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            Status status, Instant now, Pageable pageable);

    List<WebhookDelivery> findByWebhookIdOrderByCreatedAtDesc(UUID webhookId, Pageable pageable);
}
