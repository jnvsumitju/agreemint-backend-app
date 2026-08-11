package com.agreemint.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sweep that warns about documents due to expire soon.
 *
 * <p>A thin component rather than a {@code @Scheduled} method on the service,
 * for the same reason as {@code ApiAccessGraceJob}: a self-invoked
 * {@code @Transactional} method is not proxied, so the transaction would
 * silently never start. Keeping the schedule here and the work there means the
 * proxy is always crossed.
 *
 * <p>Daily, not hourly. The counterpart {@code expireDocuments()} runs hourly
 * because acting late on an expiry is a correctness problem; this one sends
 * customer email, where being an hour earlier buys nothing and the cost of a
 * mistake is a duplicate in someone's inbox.
 */
@Component
public class DocumentExpiryWarningJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentExpiryWarningJob.class);

    private final DocumentLifecycleService lifecycleService;
    private final int leadDays;
    private final int batchSize;

    public DocumentExpiryWarningJob(
            DocumentLifecycleService lifecycleService,
            @Value("${agreemint.documents.expiry-warning-lead-days:7}") int leadDays,
            @Value("${agreemint.documents.expiry-warning-batch-size:500}") int batchSize) {
        this.lifecycleService = lifecycleService;
        this.leadDays = leadDays;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${agreemint.documents.expiry-warning-cron:0 15 9 * * *}")
    public void run() {
        try {
            int sent = lifecycleService.sendExpiryWarnings(leadDays, batchSize);
            if (sent > 0) log.info("Document expiry warnings sent: {}", sent);
        } catch (Exception e) {
            // Never let one bad row take the scheduler down — the next run picks
            // up anything still unwarned, because the marker is only written per
            // document that succeeded.
            log.error("Document expiry warning sweep failed", e);
        }
    }
}
