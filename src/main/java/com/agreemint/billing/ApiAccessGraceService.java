package com.agreemint.billing;

import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.ApiKey;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Organization;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Ends API access on a schedule the customer was warned about, rather than on
 * their next request.
 *
 * <p>The free tier can create and use API keys, so a lapsed plan is not the end
 * of API access as such — what it ends is the keys minted under the paid plan.
 * That distinction matters because {@code rate_limit_rpm} is stored on the key
 * itself: a key issued under Pro keeps its higher per-minute ceiling
 * indefinitely once the plan is gone, and nothing else in the request path
 * would ever reset it. Revoking is how the workspace comes back to the limits
 * it is actually on. The customer can immediately create a new key on free.
 *
 * <p>Two emails, each sent exactly once: one when the grace period opens, one
 * when the keys actually go. Nothing here fails a billing operation — a mail
 * outage must not roll back a webhook, and the durable record is the
 * {@code api_access_grace} row.
 */
@Service
public class ApiAccessGraceService {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessGraceService.class);

    private static final DateTimeFormatter DEADLINE = DateTimeFormatter
            .ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final ApiAccessGraceRepository graceRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;

    /** Days between a plan lapsing and its keys being revoked. */
    private final int graceDays;

    public ApiAccessGraceService(
            ApiAccessGraceRepository graceRepo,
            ApiKeyRepository apiKeyRepo,
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            EmailService emailService,
            FrontendProperties frontendProps,
            @Value("${agreemint.billing.api-grace-days:3}") int graceDays) {
        this.graceRepo = graceRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
        this.graceDays = graceDays;
    }

    /**
     * Open a grace period because this workspace's paid plan has lapsed.
     *
     * <p>Idempotent: a webhook that arrives twice, or a downgrade re-applied on
     * a retry, must not restart the clock or re-send the warning.
     */
    @Transactional
    public void onPlanLapsed(UUID orgId) {
        if (orgId == null) return;
        if (graceRepo.existsById(orgId)) return;

        // Nothing to take away. Skipping here means a free workspace that never
        // had keys is not emailed about losing them.
        List<ApiKey> keys = activeKeys(orgId);
        if (keys.isEmpty()) return;

        ApiAccessGrace grace = new ApiAccessGrace();
        grace.setOrgId(orgId);
        grace.setLapsedAt(Instant.now());
        graceRepo.save(grace);
        log.info("API access grace opened for org {} — {} key(s), {} days", orgId, keys.size(), graceDays);
    }

    /**
     * Cancel a pending revocation because the workspace is paying again.
     *
     * <p>The row is deleted, not marked: a grace period that was called off is
     * not something that happened to the customer, and leaving it behind would
     * make {@link #onPlanLapsed} skip a genuine future lapse.
     */
    @Transactional
    public void onPlanReactivated(UUID orgId) {
        if (orgId == null) return;
        graceRepo.findById(orgId).ifPresent(g -> {
            if (g.getRevokedAt() != null) {
                // Already revoked — the keys are gone and a new one is needed.
                // Clearing the row lets a later lapse open a fresh grace period.
                graceRepo.delete(g);
                return;
            }
            graceRepo.delete(g);
            log.info("API access grace cancelled for org {} — plan reactivated", orgId);
        });
    }

    /** Send the "your access ends on <date>" email for any grace not yet warned. */
    @Transactional
    public int sendPendingWarnings() {
        List<ApiAccessGrace> pending = graceRepo.findByRevokedAtIsNullAndWarnedAtIsNull();
        int sent = 0;
        for (ApiAccessGrace g : pending) {
            Organization org = orgRepo.findById(g.getOrgId()).orElse(null);
            if (org == null) continue;
            List<ApiKey> keys = activeKeys(g.getOrgId());
            if (keys.isEmpty()) {
                // Revoked or expired by hand in the meantime; nothing to warn about.
                graceRepo.delete(g);
                continue;
            }
            String deadline = DEADLINE.format(g.getLapsedAt().plus(Duration.ofDays(graceDays)));
            for (String email : adminEmails(g.getOrgId())) {
                emailService.sendApiAccessEndingEmail(
                        email, org.getName(), keys.size(), deadline, billingUrl());
                sent++;
            }
            g.setWarnedAt(Instant.now());
            g.setUpdatedAt(Instant.now());
            graceRepo.save(g);
        }
        return sent;
    }

    /** Revoke keys for any grace period that has run out, then say so. */
    @Transactional
    public int revokeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(graceDays));
        List<ApiAccessGrace> due = graceRepo.findByRevokedAtIsNullAndLapsedAtBefore(cutoff);
        int revoked = 0;
        for (ApiAccessGrace g : due) {
            Organization org = orgRepo.findById(g.getOrgId()).orElse(null);
            List<ApiKey> keys = activeKeys(g.getOrgId());

            Instant now = Instant.now();
            for (ApiKey k : keys) {
                k.setRevokedAt(now);
                apiKeyRepo.save(k);
                revoked++;
            }
            g.setRevokedAt(now);
            g.setUpdatedAt(now);
            graceRepo.save(g);

            if (org != null && !keys.isEmpty()) {
                for (String email : adminEmails(g.getOrgId())) {
                    emailService.sendApiAccessRevokedEmail(
                            email, org.getName(), keys.size(), billingUrl());
                }
            }
            log.info("API access revoked for org {} — {} key(s)", g.getOrgId(), keys.size());
        }
        return revoked;
    }

    private List<ApiKey> activeKeys(UUID orgId) {
        Instant now = Instant.now();
        return apiKeyRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(k -> k.getRevokedAt() == null)
                .filter(k -> k.getExpiresAt() == null || k.getExpiresAt().isAfter(now))
                .toList();
    }

    /**
     * Admins only. A workspace's billing state is an admin concern, and mailing
     * every member that the company's API keys are going is noise for most of
     * them.
     */
    private List<String> adminEmails(UUID orgId) {
        return membershipRepo.findByOrganizationId(orgId).stream()
                .filter(m -> m.getRole() == OrgRole.ADMIN)
                .map((OrgMembership m) -> m.getUser().getEmail())
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .toList();
    }

    private String billingUrl() {
        return frontendProps.getBaseUrl() + "/settings?tab=billing";
    }
}
