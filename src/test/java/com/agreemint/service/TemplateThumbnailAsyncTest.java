package com.agreemint.service;

import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.config.ThumbnailExecutorConfig;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateDraft;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.TemplateDraftRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rendering a preview image no longer happens on the thread that committed.
 *
 * <p>It used to: an iText render, a 150-DPI rasterise and up to two uploads to
 * object storage, all inside the commit transaction and therefore all while its
 * pooled database connection was held open, with the author waiting.
 *
 * <p>Three properties, and the ordering one is the reason this is an event
 * rather than a plain {@code @Async} call. The handler runs {@code AFTER_COMMIT},
 * so a commit that rolls back publishes nothing and cannot leave a thumbnail of
 * a version that does not exist. It also means the draft row has already been
 * deleted by the time the handler runs, which is why the event carries ids and
 * the handler re-reads the version.
 */
class TemplateThumbnailAsyncTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID templateId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private TemplateRepository templateRepo;
    private TemplateDraftRepository draftRepo;
    private TemplateVersionRepository versionRepo;
    private TemplateVersionService versionService;
    private TemplateThumbnailService thumbnails;
    private ApplicationEventPublisher events;
    private TemplateDraftService service;

    private ObjectNode layout(String marker) {
        ObjectNode n = mapper.createObjectNode();
        n.put("marker", marker);
        return n;
    }

    @BeforeEach
    void setUp() {
        templateRepo = mock(TemplateRepository.class);
        draftRepo = mock(TemplateDraftRepository.class);
        versionRepo = mock(TemplateVersionRepository.class);
        versionService = mock(TemplateVersionService.class);
        thumbnails = mock(TemplateThumbnailService.class);
        events = mock(ApplicationEventPublisher.class);

        TemplateReviewService reviews = mock(TemplateReviewService.class);
        when(reviews.blockingReviewsForLatestVersion(templateId)).thenReturn(List.of());

        TemplateDraft draft = new TemplateDraft();
        draft.setTemplateId(templateId);
        draft.setLayoutJson(layout("from-draft"));
        draft.setVariables(mapper.createObjectNode().put("company.name", "Northwind"));
        when(draftRepo.findById(templateId)).thenReturn(Optional.of(draft));

        when(versionService.createVersion(any(), any())).thenReturn(
                new TemplateVersionResponse(versionId, templateId, 2,
                        layout("from-version"), mapper.createObjectNode(), Instant.now()));

        service = new TemplateDraftService(templateRepo, draftRepo, versionService, reviews,
                mock(WebhookService.class), thumbnails, versionRepo, events,
                // Same thread, so a submitted capture is observable in-test.
                Runnable::run);
    }

    @Test
    void committingPublishesTheRenderInsteadOfDoingItInline() {
        service.commitDraft(templateId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertEquals(new TemplateCommittedEvent(templateId, versionId), published.getValue());

        // The whole point: the committing thread does no rendering and no
        // uploading, so the commit transaction closes without waiting on either.
        verify(thumbnails, never()).captureCommitted(any(), any(), any());
        verify(thumbnails, never()).captureDraft(any(), any(), any());
    }

    @Test
    void theEventNamesTheVersionThatWasActuallyWritten() {
        // Not the draft, and not a guess. createVersion substitutes a default
        // layout when the draft's is null, so a payload built from the draft
        // could describe a different document than the row that was stored —
        // in precisely the case nobody would notice.
        service.commitDraft(templateId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertEquals(versionId, ((TemplateCommittedEvent) published.getValue()).versionId());
    }

    @Test
    void theHandlerRendersFromTheCommittedVersionRow() {
        // The draft row is deleted inside the commit transaction, so by the time
        // this runs there is nothing else left to read.
        TemplateVersion v = new TemplateVersion();
        v.setLayoutJson(layout("from-version"));
        v.setVariables(mapper.createObjectNode().put("k", "v"));
        when(versionRepo.findById(versionId)).thenReturn(Optional.of(v));

        new TemplateThumbnailListener(versionRepo, thumbnails)
                .onTemplateCommitted(new TemplateCommittedEvent(templateId, versionId));

        ArgumentCaptor<JsonNode> layoutArg = ArgumentCaptor.forClass(JsonNode.class);
        verify(thumbnails).captureCommitted(org.mockito.ArgumentMatchers.eq(templateId),
                layoutArg.capture(), any());
        assertEquals("from-version", layoutArg.getValue().path("marker").asText());
    }

    @Test
    void aVersionDeletedBeforeTheHandlerRunsIsNotAnError() {
        when(versionRepo.findById(versionId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> new TemplateThumbnailListener(versionRepo, thumbnails)
                .onTemplateCommitted(new TemplateCommittedEvent(templateId, versionId)));

        verify(thumbnails, never()).captureCommitted(any(), any(), any());
    }

    @Test
    void theHandlerSwallowsFailuresBecauseNothingUpstreamCanCatchThem() {
        // On a pool thread an exception from a void @Async method goes to the
        // executor's uncaught handler and disappears. Losing a thumbnail is
        // acceptable; losing the reason is not, so it is caught and logged here.
        when(versionRepo.findById(versionId)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> new TemplateThumbnailListener(versionRepo, thumbnails)
                .onTemplateCommitted(new TemplateCommittedEvent(templateId, versionId)));
    }

    // ── the 60-second capture cannot flood the shared queue ───────────────────

    /** Collects submissions without running them, so "queued" is observable. */
    private static final class DeferredExecutor implements java.util.concurrent.Executor {
        final List<Runnable> queued = new java.util.ArrayList<>();
        @Override public void execute(Runnable r) { queued.add(r); }
        void drain() {
            List<Runnable> now = List.copyOf(queued);
            queued.clear();
            now.forEach(Runnable::run);
        }
    }

    private TemplateDraftService serviceOn(java.util.concurrent.Executor ex) {
        TemplateReviewService reviews = mock(TemplateReviewService.class);
        when(reviews.blockingReviewsForLatestVersion(any())).thenReturn(List.of());
        return new TemplateDraftService(templateRepo, draftRepo, versionService, reviews,
                mock(WebhookService.class), thumbnails, versionRepo, events, ex);
    }

    @Test
    void repeatedCaptureRequestsForOneTemplateQueueOnlyOneRender() {
        // The endpoint answers 204 before any work starts, so the client gets no
        // backpressure and nothing stops it looping. The queue is shared with
        // the after-commit renders, which are never retried — so one editor
        // filling it would mean permanently stale thumbnails for everyone else.
        DeferredExecutor ex = new DeferredExecutor();
        TemplateDraftService svc = serviceOn(ex);

        for (int i = 0; i < 25; i++) svc.captureDraftThumbnail(templateId);

        assertEquals(1, ex.queued.size(), "25 requests for the same template must queue one render");
    }

    @Test
    void adifferentTemplateIsNotBlockedByOneAlreadyPending() {
        DeferredExecutor ex = new DeferredExecutor();
        TemplateDraftService svc = serviceOn(ex);

        svc.captureDraftThumbnail(templateId);
        svc.captureDraftThumbnail(UUID.randomUUID());

        assertEquals(2, ex.queued.size(), "coalescing is per template, not a global lock");
    }

    @Test
    void theSlotIsReleasedOnceTheRenderHasRun() {
        // Otherwise the first capture would be the only one a template ever got.
        DeferredExecutor ex = new DeferredExecutor();
        TemplateDraftService svc = serviceOn(ex);

        svc.captureDraftThumbnail(templateId);
        ex.drain();
        svc.captureDraftThumbnail(templateId);

        assertEquals(1, ex.queued.size());
    }

    @Test
    void aRejectedSubmissionDoesNotLeaveTheTemplateBlockedForever() {
        // A full queue throws RejectedExecutionException out of execute(). If
        // the slot were not released, that template would never be captured
        // again for the lifetime of the process.
        java.util.concurrent.Executor rejecting = r -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
        TemplateDraftService svc = serviceOn(rejecting);
        assertDoesNotThrow(() -> svc.captureDraftThumbnail(templateId));

        DeferredExecutor ex = new DeferredExecutor();
        TemplateDraftService retry = serviceOn(ex);
        retry.captureDraftThumbnail(templateId);
        assertEquals(1, ex.queued.size());
    }

    // ── the pool ──────────────────────────────────────────────────────────────

    @Test
    void theExecutorIsBoundedRatherThanOneThreadPerRender() {
        // Every @Async method in this application currently runs on a
        // SimpleAsyncTaskExecutor — a new unbounded platform thread per call —
        // because Boot's applicationTaskExecutor backs off when the WebSocket
        // config registers Executor beans. That is survivable for email. Each
        // render here holds an ~8 MB BufferedImage, so unbounded concurrency
        // turns a burst of commits into an OutOfMemoryError.
        ThreadPoolExecutor ex = new ThumbnailExecutorConfig().thumbnailExecutor(2, 50);

        assertEquals(2, ex.getCorePoolSize());
        assertEquals(2, ex.getMaximumPoolSize(), "core == max: a pool only grows past core once "
                + "the queue is FULL, so max > core with a large queue is never reached");
        assertEquals(50, ex.getQueue().remainingCapacity());
        ex.shutdownNow();
    }

    @Test
    void aFullQueueDropsTheRenderInsteadOfRunningItOnTheCallersThread() {
        ThreadPoolExecutor ex = new ThumbnailExecutorConfig().thumbnailExecutor(1, 1);
        RejectedExecutionHandler handler = ex.getRejectedExecutionHandler();

        AtomicBoolean ran = new AtomicBoolean(false);
        assertDoesNotThrow(() -> handler.rejectedExecution(() -> ran.set(true), ex));

        // CallerRunsPolicy would execute the render on whichever thread
        // submitted it — after a commit that is a Tomcat request thread, i.e.
        // exactly the thread this design exists to protect.
        assertFalse(ran.get(), "a rejected render must be dropped, not run on the caller");
        ex.shutdownNow();
    }
}
