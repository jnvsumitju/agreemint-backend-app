package com.agreemint.admin.service;

import com.agreemint.admin.domain.StaffExport;
import com.agreemint.admin.repository.StaffExportRepository;
import com.agreemint.domain.*;
import com.agreemint.repository.*;
import com.agreemint.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Polls the staff export queue.

 * <p>Deliberately separate from {@link StaffExportProcessor}: the work needs a
 * transaction for lazy associations, and a @Transactional method invoked from
 * another method on the same bean bypasses Spring's proxy entirely — so it
 * would silently run without one.
 *
 * <p>Before this existed, {@code POST /api/admin/exports} wrote a PENDING row
 * and nothing ever moved it — every export sat unfinished forever while the UI
 * had no way to know.
 *
 * <p>Modelled on {@code WebhookDispatchJob}: poll, claim, do the work, record
 * the outcome. Claiming uses a conditional update so two instances polling the
 * same queue cannot process one export twice.
 *
 * <p>Output is JSON written to the private documents bucket. Downloads go
 * through {@code GET /api/admin/exports/{id}/file}, which redirects to a
 * short-lived presigned URL — the object is never public.
 */
@Service
public class StaffExportJob {

    private static final Logger log = LoggerFactory.getLogger(StaffExportJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on rows written into a single export, so one job cannot run away. */
    private static final int MAX_ROWS = 10_000;

    private final StaffExportRepository exportRepo;
    private final StaffExportProcessor processor;

    public StaffExportJob(StaffExportRepository exportRepo, StaffExportProcessor processor) {
        this.exportRepo = exportRepo;
        this.processor = processor;
    }

    /** Storage key for an export's payload. */
    public static String objectKey(UUID exportId) {
        return "staff-exports/" + exportId + ".json";
    }

    @Scheduled(fixedDelayString = "${agreemint.exports.poll-interval-ms:5000}")
    public void pollQueue() {
        List<StaffExport> pending =
                exportRepo.findTop10ByStatusOrderByRequestedAtAsc(StaffExport.Status.PENDING.name());
        for (StaffExport export : pending) {
            // Lost the race to another instance — skip without touching it.
            if (exportRepo.claim(export.getId(),
                    StaffExport.Status.PENDING.name(),
                    StaffExport.Status.PROCESSING.name()) != 1) {
                continue;
            }
            try {
                processor.process(export.getId());
            } catch (RuntimeException e) {
                // Recorded outside the failed transaction — see markFailed. Also
                // keeps one bad export from aborting the rest of the batch, which
                // is what an unhandled throw here used to do.
                processor.markFailed(export.getId(), e.getMessage());
            }
        }
    }
}
