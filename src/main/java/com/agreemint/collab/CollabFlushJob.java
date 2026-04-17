package com.agreemint.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Every 5 s, flushes the hot Redis layout of each dirty template into Postgres
 * via {@link CollabService#flushIfDirty(UUID)}. The commit/version flow reads from
 * Postgres so this frequency bounds how stale a committed version can be.
 */
@Component
public class CollabFlushJob {

    private static final Logger log = LoggerFactory.getLogger(CollabFlushJob.class);

    private final CollabService collabService;

    public CollabFlushJob(CollabService collabService) {
        this.collabService = collabService;
    }

    @Scheduled(fixedDelayString = "${agreemint.collab.flush-interval-ms:5000}")
    public void run() {
        Set<UUID> dirty = collabService.dirtyTemplates();
        if (dirty.isEmpty()) return;

        int flushed = 0;
        for (UUID id : dirty) {
            try {
                if (collabService.flushIfDirty(id)) flushed++;
            } catch (RuntimeException ex) {
                log.warn("Flush error for template {}: {}", id, ex.getMessage());
            }
        }
        if (flushed > 0) {
            log.info("Collab flush persisted {} / {} dirty templates", flushed, dirty.size());
        }
    }
}
