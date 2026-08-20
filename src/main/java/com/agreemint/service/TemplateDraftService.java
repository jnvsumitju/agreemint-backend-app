package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateDraftResponse;
import com.agreemint.api.dto.TemplateReviewResponse;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.api.dto.UpsertDraftRequest;
import com.agreemint.domain.TemplateDraft;
import com.agreemint.repository.TemplateDraftRepository;
import com.agreemint.repository.TemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TemplateDraftService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TemplateDraftService.class);

    private final TemplateRepository templateRepository;
    private final TemplateDraftRepository templateDraftRepository;
    private final TemplateVersionService templateVersionService;
    private final TemplateReviewService templateReviewService;
    private final com.agreemint.service.TemplateThumbnailService thumbnailService;
    private final com.agreemint.repository.TemplateVersionRepository templateVersionRepository;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final java.util.concurrent.Executor thumbnailExecutor;
    private final WebhookService webhookService;

    /**
     * Templates with a draft capture already queued or running.
     *
     * <p>Every open editor posts to the capture endpoint once a minute and the
     * endpoint answers 204 before any work starts, so the client gets no
     * backpressure and nothing stops it looping. Without this, one editor can
     * fill the shared render queue on its own — and the queue is shared with
     * the after-commit renders, which are not retried, so the flood would turn
     * into permanently stale thumbnails for everyone else.
     *
     * <p>Coalescing rather than rate-limiting because a second capture of the
     * same template while the first is still running would render the same
     * bytes twice and race to write the same key.
     */
    private final java.util.Set<UUID> draftCapturesInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TemplateDraftService(
            TemplateRepository templateRepository,
            TemplateDraftRepository templateDraftRepository,
            TemplateVersionService templateVersionService,
            @Lazy TemplateReviewService templateReviewService,
            WebhookService webhookService,
            com.agreemint.service.TemplateThumbnailService thumbnailService,
            com.agreemint.repository.TemplateVersionRepository templateVersionRepository,
            org.springframework.context.ApplicationEventPublisher events,
            @org.springframework.beans.factory.annotation.Qualifier(
                    com.agreemint.config.ThumbnailExecutorConfig.EXECUTOR)
            java.util.concurrent.Executor thumbnailExecutor) {
        this.templateRepository = templateRepository;
        this.templateDraftRepository = templateDraftRepository;
        this.templateVersionService = templateVersionService;
        this.templateReviewService = templateReviewService;
        this.webhookService = webhookService;
        this.thumbnailService = thumbnailService;
        this.templateVersionRepository = templateVersionRepository;
        this.events = events;
        this.thumbnailExecutor = thumbnailExecutor;
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDraftResponse> getDraft(UUID templateId) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        return templateDraftRepository.findById(templateId).map(this::toResponse);
    }

    @Transactional
    public TemplateDraftResponse upsertDraft(UUID templateId, UpsertDraftRequest request) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        JsonNode layout = request.layout();
        if (layout == null || layout.isNull()) {
            throw new BadRequestException("layout is required");
        }
        JsonNode variables = request.variables();
        if (variables == null || variables.isNull()) {
            variables = JsonNodeFactory.instance.objectNode();
        }

        TemplateDraft d = templateDraftRepository.findById(templateId).orElseGet(() -> {
            TemplateDraft n = new TemplateDraft();
            n.setTemplateId(templateId);
            return n;
        });
        d.setLayoutJson(layout);
        d.setVariables(variables);
        d.setUpdatedAt(Instant.now());
        templateDraftRepository.save(d);
        return toResponse(d);
    }

    /**
     * Persist ONLY the draft {@code variables} while preserving the current
     * {@code layoutJson}. Counterpart to {@link #saveFromCollabFlush}, which
     * persists only the layout. Splitting the two write paths avoids a race:
     * the collab flush job owns layout; client-initiated debounced saves own
     * variables. If either side used the full {@link #upsertDraft} they would
     * clobber the other domain with a stale copy.
     *
     * <p>The frontend triggers this whenever a body-cell edit (or any other
     * variable-value mutation) hits the store. Without it, typed preview data
     * vanished on reload because the collab layer only carries layout ops +
     * variable <em>definitions</em> — not variable <em>values</em>.
     */
    @Transactional
    public void upsertDraftVariables(UUID templateId, JsonNode variables) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        JsonNode vars = (variables == null || variables.isNull())
                ? JsonNodeFactory.instance.objectNode()
                : variables;
        TemplateDraft d = templateDraftRepository.findById(templateId).orElseGet(() -> {
            TemplateDraft n = new TemplateDraft();
            n.setTemplateId(templateId);
            n.setLayoutJson(JsonNodeFactory.instance.objectNode());
            return n;
        });
        d.setVariables(vars);
        d.setUpdatedAt(Instant.now());
        templateDraftRepository.save(d);
    }

    /**
     * Apply one editor's variable changes without discarding anyone else's.
     *
     * <p>The whole-map PUT this replaces was last-writer-wins over every key at
     * once: two people editing different variables in the same 800ms debounce
     * window meant whichever request landed second silently erased the other's
     * work. Nothing errored and nothing was logged — the value simply reverted
     * under them a moment after they typed it.
     *
     * <p>A patch fixes that because each client only ever asserts the keys IT
     * changed. Everything it did not touch is left exactly as the row holds it,
     * so unrelated concurrent edits compose instead of racing.
     *
     * <p><b>Removals are explicit, and have to be.</b> An add-only merge looks
     * simpler and is wrong here: the editor deletes keys for real — renaming a
     * variable drops the old key, and {@code mergeVariableValues} prunes any key
     * the layout no longer references. Without carrying removals, a rename
     * would resurrect the old key on the next save from any other client.
     *
     * <p>Same-key concurrent edits are still last-writer-wins, deliberately.
     * Merging two people's text for one field needs a CRDT, and variable values
     * are plain state rather than Yjs — see the note in
     * {@code src/collab/yDocProvider.ts}. This narrows the loss from "every
     * variable in the template" to "the one field you were both typing in",
     * which is the outcome a person can actually understand.
     */
    @Transactional
    public void patchDraftVariables(UUID templateId, JsonNode set, JsonNode remove) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        TemplateDraft d = templateDraftRepository.findById(templateId).orElseGet(() -> {
            TemplateDraft n = new TemplateDraft();
            n.setTemplateId(templateId);
            n.setLayoutJson(JsonNodeFactory.instance.objectNode());
            n.setVariables(JsonNodeFactory.instance.objectNode());
            return n;
        });

        JsonNode existing = d.getVariables();
        ObjectNode merged = existing != null && existing.isObject()
                ? existing.deepCopy()
                : JsonNodeFactory.instance.objectNode();

        if (set != null && set.isObject()) {
            set.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        }
        if (remove != null && remove.isArray()) {
            for (JsonNode key : remove) {
                if (key != null && key.isTextual()) merged.remove(key.asText());
            }
        }

        d.setVariables(merged);
        d.setUpdatedAt(Instant.now());
        templateDraftRepository.save(d);
    }

    /**
     * Persist a layout produced by the collaborative-editor flush job.
     * Runs outside any user context — authorisation is enforced on every op that
     * built up this layout, so a flush does not need to re-check.
     * Preserves existing {@code variables}; only updates {@code layoutJson} and {@code updatedAt}.
     */
    @Transactional
    public void saveFromCollabFlush(UUID templateId, JsonNode layout) {
        if (layout == null || layout.isNull()) return;
        if (!templateRepository.existsById(templateId)) return;

        TemplateDraft d = templateDraftRepository.findById(templateId).orElseGet(() -> {
            TemplateDraft n = new TemplateDraft();
            n.setTemplateId(templateId);
            n.setVariables(JsonNodeFactory.instance.objectNode());
            return n;
        });
        d.setLayoutJson(layout);
        if (d.getVariables() == null) {
            d.setVariables(JsonNodeFactory.instance.objectNode());
        }
        d.setUpdatedAt(Instant.now());
        templateDraftRepository.save(d);
    }

    @Transactional
    public TemplateVersionResponse commitDraft(UUID templateId) {
        // Commit gate: any review on the CURRENT LATEST committed version that is
        // still CHANGES_REQUESTED blocks the next commit. Designer must either
        // have the reviewer re-evaluate (→ APPROVED) or dismiss the review.
        List<TemplateReviewResponse> blockers =
                templateReviewService.blockingReviewsForLatestVersion(templateId);
        if (!blockers.isEmpty()) {
            String names = blockers.stream()
                    .map(b -> b.reviewer().name())
                    .distinct()
                    .collect(Collectors.joining(", "));
            throw new ReviewBlockException(
                    "Cannot commit: mandatory changes requested by " + names
                            + ". Address the feedback (re-request review) or dismiss the review(s).",
                    blockers);
        }

        TemplateDraft d = templateDraftRepository.findById(templateId)
                .orElseThrow(() -> new BadRequestException(
                        "No draft to commit. Wait for autosave or edit the template first."));
        JsonNode vars = d.getVariables();
        if (vars == null || vars.isNull()) {
            vars = JsonNodeFactory.instance.objectNode();
        }
        CreateVersionRequest req = new CreateVersionRequest(d.getLayoutJson(), vars);
        TemplateVersionResponse created = templateVersionService.createVersion(templateId, req);

        // Preview image for what was just committed, rendered after this
        // transaction commits rather than inside it. The render, the rasterise
        // and the uploads used to run here, holding this transaction's database
        // connection open while the author waited for a picture.
        //
        // Published rather than called: if the commit rolls back below this
        // line, no event is dispatched and no thumbnail is made of a version
        // that does not exist.
        events.publishEvent(new TemplateCommittedEvent(templateId, created.id()));

        templateDraftRepository.deleteById(templateId);

        UUID orgId = templateRepository.findById(templateId).map(t -> t.getOrgId()).orElse(null);
        if (orgId != null) {
            webhookService.emit(orgId, "template.version.committed", java.util.Map.of(
                    "templateId", templateId.toString(),
                    "versionId", created.id().toString(),
                    "versionNumber", created.versionNumber(),
                    "createdAt", created.createdAt() == null ? "" : created.createdAt().toString()
            ));
        }
        return created;
    }

    /**
     * Render the current draft into the in-progress preview.
     *
     * <p>Falls back to the latest committed version when there is no draft, so
     * a template that has been committed and not touched since still gets an
     * image rather than staying blank forever.
     *
     * <p>Runs on the thumbnail pool, so the endpoint that triggers it answers
     * immediately. It is called once a minute by every open editor and the
     * caller ignores the response; making a browser hold a request open for a
     * PDF render it will not look at buys nobody anything.
     *
     * <p>Deliberately NOT {@code @Transactional}: the repository calls below
     * are individually transactional, and wrapping them would hold a pooled
     * connection for the whole render. Both capture methods open their own
     * short transaction for the row they update.
     *
     * <p>Submitted directly rather than via {@code @Async} so the coalescing
     * check happens BEFORE the task is queued — an {@code @Async} method is
     * already on the queue by the time its body could look.
     */
    public void captureDraftThumbnail(UUID templateId) {
        if (!draftCapturesInFlight.add(templateId)) {
            log.debug("[thumbnail] Draft capture already pending for {}; skipping", templateId);
            return;
        }
        try {
            thumbnailExecutor.execute(() -> {
                try {
                    renderDraftThumbnail(templateId);
                } finally {
                    draftCapturesInFlight.remove(templateId);
                }
            });
        } catch (RuntimeException e) {
            // A full queue rejects here; the handler logs it. Release the slot
            // so the next minute's attempt is not blocked by a capture that
            // never ran.
            draftCapturesInFlight.remove(templateId);
        }
    }

    private void renderDraftThumbnail(UUID templateId) {
        try {
            var draft = templateDraftRepository.findById(templateId);
            if (draft.isPresent()) {
                thumbnailService.captureDraft(templateId, draft.get().getLayoutJson(),
                        draft.get().getVariables());
                return;
            }
            // Latest committed version. The previous version of this asked
            // getVersionEntity for a null versionId on the belief that null
            // meant "latest" — it does not; that resolver calls findById(null),
            // which throws IllegalArgumentException straight into the catch. So
            // this branch never produced an image for any template, which is
            // exactly the case it exists to cover.
            templateRepository.findById(templateId)
                    .flatMap(templateVersionRepository::findFirstByTemplateOrderByVersionNumberDesc)
                    .ifPresent(v -> thumbnailService.captureCommitted(
                            templateId, v.getLayoutJson(), v.getVariables()));
        } catch (Throwable t) {
            // On a pool thread there is no caller to receive this.
            log.warn("[thumbnail] Draft capture failed for {}: {}", templateId, t.toString());
        }
    }

    private TemplateDraftResponse toResponse(TemplateDraft d) {
        return new TemplateDraftResponse(d.getLayoutJson(), d.getVariables(), d.getUpdatedAt());
    }
}
