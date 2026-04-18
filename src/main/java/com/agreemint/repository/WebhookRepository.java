package com.agreemint.repository;

import com.agreemint.domain.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

    /** Active webhooks for an org. Callers still check per-event subscription. */
    List<Webhook> findByOrgIdAndActiveTrueAndRevokedAtIsNullOrderByCreatedAtDesc(UUID orgId);

    /** All webhooks for an org, including revoked — used by the Developer tab list. */
    List<Webhook> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
