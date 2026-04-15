package com.agreemint.service;

import com.agreemint.api.dto.TemplateShareResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.TemplateShare;
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

    public TemplateShareService(TemplateShareRepository shareRepo, UserRepository userRepo) {
        this.shareRepo = shareRepo;
        this.userRepo = userRepo;
    }

    /** Share a template with a user by email. */
    @Transactional
    public TemplateShareResponse shareWithUser(UUID templateId, String email, OrgRole role, UUID createdByUserId) {
        var user = userRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        // Check for existing share
        var existing = shareRepo.findByTemplateIdAndSharedWithUserId(templateId, user.getId());
        if (existing.isPresent()) {
            // Update role
            var share = existing.get();
            share.setRole(role);
            return TemplateShareResponse.from(shareRepo.save(share));
        }

        TemplateShare share = new TemplateShare();
        share.setTemplateId(templateId);
        share.setSharedWithUserId(user.getId());
        share.setSharedWithEmail(user.getEmail());
        share.setRole(role);
        share.setCreatedBy(createdByUserId);
        return TemplateShareResponse.from(shareRepo.save(share));
    }

    /** Generate a share link with a random token. */
    @Transactional
    public TemplateShareResponse generateShareLink(UUID templateId, OrgRole role, UUID createdByUserId, Integer expiresInHours) {
        TemplateShare share = new TemplateShare();
        share.setTemplateId(templateId);
        share.setRole(role);
        share.setShareToken(UUID.randomUUID().toString());
        share.setCreatedBy(createdByUserId);
        if (expiresInHours != null && expiresInHours > 0) {
            share.setExpiresAt(Instant.now().plusSeconds(expiresInHours * 3600L));
        }
        return TemplateShareResponse.from(shareRepo.save(share));
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
