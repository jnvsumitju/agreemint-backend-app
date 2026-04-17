package com.agreemint.service;

import com.agreemint.api.dto.TemplateShareResponse;
import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateShare;
import com.agreemint.domain.User;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateShareRepository;
import com.agreemint.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TemplateShareService {

    private final TemplateShareRepository shareRepo;
    private final UserRepository userRepo;
    private final TemplateRepository templateRepo;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;

    public TemplateShareService(
            TemplateShareRepository shareRepo,
            UserRepository userRepo,
            TemplateRepository templateRepo,
            NotificationService notificationService,
            EmailService emailService,
            FrontendProperties frontendProps) {
        this.shareRepo = shareRepo;
        this.userRepo = userRepo;
        this.templateRepo = templateRepo;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
    }

    /**
     * Share a template with a user by email — simplified "pointer" flow.
     *
     * <p>Role is hard-coded to VIEWER because actual access is governed by the
     * recipient's org membership; the share row exists only so the target sees
     * a "shared with me" list entry. The recipient is also:
     * <ul>
     *     <li>sent a {@code TEMPLATE_SHARED} in-app notification (if registered), and</li>
     *     <li>emailed a heads-up link to the template.</li>
     * </ul>
     */
    @Transactional
    public TemplateShareResponse shareWithUser(UUID templateId, String email, UUID createdByUserId) {
        String normalized = email == null ? "" : email.toLowerCase().trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }

        Template template = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        User sharer = userRepo.findById(createdByUserId).orElse(null);
        User target = userRepo.findByEmail(normalized).orElse(null);

        TemplateShare share;
        if (target != null) {
            var existing = shareRepo.findByTemplateIdAndSharedWithUserId(templateId, target.getId());
            share = existing.orElseGet(TemplateShare::new);
            share.setTemplateId(templateId);
            share.setSharedWithUserId(target.getId());
            share.setSharedWithEmail(target.getEmail());
            share.setRole(OrgRole.VIEWER);
            if (share.getCreatedBy() == null) share.setCreatedBy(createdByUserId);
        } else {
            // Target isn't a registered Agreemint user — still record the share by
            // email so the sharer's UI shows their pending invites consistently.
            share = new TemplateShare();
            share.setTemplateId(templateId);
            share.setSharedWithEmail(normalized);
            share.setRole(OrgRole.VIEWER);
            share.setCreatedBy(createdByUserId);
        }
        TemplateShare saved = shareRepo.save(share);

        // In-app notification (only for registered users — no user → no inbox).
        if (target != null) {
            String sharerName = sharer != null ? sharer.getName() : "Someone";
            notificationService.notify(
                    target.getId(),
                    "TEMPLATE_SHARED",
                    sharerName + " shared \"" + template.getName() + "\" with you",
                    "Open the template to take a look.",
                    "TEMPLATE",
                    templateId);
        }
        // Email notification always.
        String sharerName = sharer != null ? sharer.getName() : "A teammate";
        emailService.sendTemplateSharedEmail(
                target != null ? target.getEmail() : normalized,
                template.getName(),
                sharerName,
                templateEditorUrl(templateId));

        return TemplateShareResponse.from(saved);
    }

    /**
     * Generate a share link. The link is just a pointer URL — VIEWER role applies
     * if the recipient opens it without an existing org membership.
     */
    @Transactional
    public TemplateShareResponse generateShareLink(UUID templateId, UUID createdByUserId, Integer expiresInHours) {
        TemplateShare share = new TemplateShare();
        share.setTemplateId(templateId);
        share.setRole(OrgRole.VIEWER);
        share.setShareToken(UUID.randomUUID().toString());
        share.setCreatedBy(createdByUserId);
        if (expiresInHours != null && expiresInHours > 0) {
            share.setExpiresAt(Instant.now().plusSeconds(expiresInHours * 3600L));
        }
        return TemplateShareResponse.from(shareRepo.save(share));
    }

    private String templateEditorUrl(UUID templateId) {
        String base = frontendProps.getBaseUrl();
        if (base == null || base.isEmpty()) base = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/templates/" + templateId;
    }

    /** Resolve a share token, checking expiry. */
    @Transactional(readOnly = true)
    public TemplateShare resolveShareToken(String token) {
        TemplateShare share = shareRepo.findByShareToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid share link"));
        if (share.getExpiresAt() != null && share.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Share link has expired");
        }
        return share;
    }

    /** List all shares for a template. */
    @Transactional(readOnly = true)
    public List<TemplateShareResponse> listShares(UUID templateId) {
        return shareRepo.findByTemplateId(templateId).stream()
                .map(TemplateShareResponse::from)
                .toList();
    }

    /** Revoke a share. */
    @Transactional
    public void revokeShare(UUID templateId, UUID shareId) {
        TemplateShare share = shareRepo.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!share.getTemplateId().equals(templateId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        shareRepo.delete(share);
    }

    /** Get the share-based role for a user on a template (or null). */
    @Transactional(readOnly = true)
    public OrgRole getUserShareRole(UUID templateId, UUID userId) {
        return shareRepo.findByTemplateIdAndSharedWithUserId(templateId, userId)
                .map(TemplateShare::getRole)
                .orElse(null);
    }
}
