package com.agreemint.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the API-access grace period on a timer.
 *
 * <p>Split from {@link ApiAccessGraceService} for the same reason
 * {@code StaffExportJob} is split from its processor: the transactional work
 * has to be invoked through the Spring proxy, and a {@code @Transactional}
 * method called from within its own class is not.
 *
 * <p>Hourly rather than daily. The deadline is a date, so hourly costs nothing
 * extra in emails, but it means a lapse detected at 09:05 does not wait until
 * the following morning to warn anyone.
 */
@Component
public class ApiAccessGraceJob {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessGraceJob.class);

    private final ApiAccessGraceService grace;

    public ApiAccessGraceJob(ApiAccessGraceService grace) {
        this.grace = grace;
    }

    @Scheduled(cron = "${agreemint.billing.api-grace-cron:0 5 * * * *}")
    public void run() {
        try {
            int warned = grace.sendPendingWarnings();
            int revoked = grace.revokeExpired();
            if (warned > 0 || revoked > 0) {
                log.info("API access grace sweep — warned {} admin(s), revoked {} key(s)",
                        warned, revoked);
            }
        } catch (RuntimeException e) {
            // Swallowed so one bad row cannot stop the scheduler forever.
            log.error("API access grace sweep failed", e);
        }
    }
}
