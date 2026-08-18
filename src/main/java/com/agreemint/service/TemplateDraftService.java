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

    private final TemplateRepository templateRepository;
    private final TemplateDraftRepository templateDraftRepository;
    private final TemplateVersionService templateVersionService;
    private final TemplateReviewService templateReviewService;
    private final com.agreemint.service.TemplateThumbnailService thumbnailService;
    private final WebhookService webhookService;

    public TemplateDraftService(
            TemplateRepository templateRepository,
            TemplateDraftRepository templateDraftRepository,
            TemplateVersionService templateVersionService,
            @Lazy TemplateReviewService templateReviewService,
            WebhookService webhookService,
            com.agreemint.service.TemplateThumbnailService thumbnailService) {
        this.templateRepository = templateRepository;
        this.templateDraftRepository = templateDraftRepository;
        this.templateVersionService = templateVersionService;
        this.templateReviewService = templateReviewService;
        this.webhookService = webhookService;
        this.thumbnailService = thumbnailService;
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

        // Preview image for what was just committed. Deliberately AFTER the
        // version is created and BEFORE nothing else depends on it — and the
        // service swallows every failure internally, so a thumbnail that will
        // not rasterise cannot cost the author their commit.
        thumbnailService.captureCommitted(templateId, d.getLayoutJson(), vars);

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
     */
    @Transactional
    public void captureDraftThumbnail(UUID templateId) {
        var draft = templateDraftRepository.findById(templateId);
        if (draft.isPresent()) {
            thumbnailService.captureDraft(templateId, draft.get().getLayoutJson(),
                    draft.get().getVariables());
            return;
        }
        // Null versionId already means "latest committed" to this resolver, so
        // there is no need for a second way to ask the same question.
        try {
            var v = templateVersionService.getVersionEntity(templateId, null);
            thumbnailService.captureCommitted(templateId, v.getLayoutJson(), v.getVariables());
        } catch (RuntimeException e) {
            // A template with no draft AND no version has nothing to draw.
            // No logger on this class; nothing to capture is not noteworthy.
        }
    }

    private TemplateDraftResponse toResponse(TemplateDraft d) {
        return new TemplateDraftResponse(d.getLayoutJson(), d.getVariables(), d.getUpdatedAt());
    }
}
