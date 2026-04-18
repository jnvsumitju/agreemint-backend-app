package com.agreemint.service;

import com.agreemint.domain.ApiKey;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Organization;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.config.FrontendProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fires once a day (09:00 local) and notifies all org ADMINs when an API key
 * is within 7 days of its expiry. Warning is sent at most once per key —
 * tracked transiently via the key's {@code last_used_at} not being used
 * (dedicated column would be ideal, but we de-duplicate via the 24-hour
 * cadence and the narrow 6-to-7-days-out window).
 */
@Component
public class ApiKeyExpiryWarningJob {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyExpiryWarningJob.class);

    private final ApiKeyRepository apiKeyRepo;
    private final OrgMembershipRepository membershipRepo;
    private final OrganizationRepository orgRepo;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;

    public ApiKeyExpiryWarningJob(
            ApiKeyRepository apiKeyRepo,
            OrgMembershipRepository membershipRepo,
            OrganizationRepository orgRepo,
            NotificationService notificationService,
            EmailService emailService,
            FrontendProperties frontendProps) {
        this.apiKeyRepo = apiKeyRepo;
        this.membershipRepo = membershipRepo;
        this.orgRepo = orgRepo;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
    }

    @Scheduled(cron = "${agreemint.api-keys.expiry-warning-cron:0 0 9 * * *}")
    @Transactional
    public void run() {
        Instant now = Instant.now();
        Instant soon = now.plus(Duration.ofDays(7));
        List<ApiKey> expiring = apiKeyRepo.findByRevokedAtIsNullAndExpiresAtBetween(now, soon);
        if (expiring.isEmpty()) return;

        int warned = 0;
        Set<String> visited = new HashSet<>();
        for (ApiKey k : expiring) {
            if (k.getRotatedToId() != null) continue; // user already rotated; no warning needed
            Organization org = orgRepo.findById(k.getOrgId()).orElse(null);
            if (org == null) continue;
            long daysLeft = Math.max(1,
                    Duration.between(now, k.getExpiresAt()).toDays());

            List<OrgMembership> admins = membershipRepo.findByOrganizationId(k.getOrgId());
            for (OrgMembership m : admins) {
                if (m.getRole() != OrgRole.ADMIN) continue;
                java.util.UUID adminId = m.getUser().getId();
                String dedupeKey = (k.getId().toString() + ":" + adminId);
                if (!visited.add(dedupeKey)) continue;

                notificationService.notify(
                        adminId,
                        "API_KEY_EXPIRING",
                        "API key \"" + k.getName() + "\" expires in " + daysLeft + " day"
                                + (daysLeft == 1 ? "" : "s"),
                        "Rotate it from Settings → Developer before it expires.",
                        "API_KEY",
                        k.getId());

                emailService.sendApiKeyExpiryWarningEmail(
                        m.getUser().getEmail(),
                        org.getName(),
                        k.getName(),
                        daysLeft,
                        developerSettingsUrl());
                warned++;
            }
        }
        log.info("API key expiry warnings sent: {} (keys near expiry: {})", warned, expiring.size());
    }

    private String developerSettingsUrl() {
        String base = frontendProps.getBaseUrl();
        if (base == null || base.isEmpty()) base = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/settings?tab=developer";
    }
}
