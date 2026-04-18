package com.agreemint.api;

import com.agreemint.domain.Organization;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.R2StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multipart avatar uploads for the current user and for the org they admin.
 * The raw bytes are written to the public R2 bucket under a deterministic
 * prefix; the returned public URL is stored back on the owning row
 * ({@code users.avatar_url} / {@code organizations.logo_url}) so
 * {@code <img src>} can render it directly without going through the backend.
 */
@Tag(name = "Avatars", description = "Upload user + org avatars to public object storage")
@RestController
public class AvatarController {

    /** Allowed image MIME types. Anything else returns 415. */
    private static final Set<String> ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/webp");

    /** Hard ceiling per upload (bytes). Matches the multipart limit in
     *  application.yml; the explicit check gives a clean 400 instead of
     *  Spring's MaxUploadSizeExceededException → 500. */
    private static final long MAX_BYTES = 3L * 1024 * 1024;

    private final R2StorageService r2;
    private final UserRepository userRepo;
    private final OrganizationRepository orgRepo;
    private final OrgAuthorizationService orgAuthz;

    public AvatarController(
            R2StorageService r2,
            UserRepository userRepo,
            OrganizationRepository orgRepo,
            OrgAuthorizationService orgAuthz) {
        this.r2 = r2;
        this.userRepo = userRepo;
        this.orgRepo = orgRepo;
        this.orgAuthz = orgAuthz;
    }

    @Operation(summary = "Replace my avatar (multipart image/* upload)")
    @PostMapping(value = "/api/users/me/avatar", consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<Map<String, String>> uploadUserAvatar(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        validate(file);
        User user = userRepo.findById(principal.userId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        String ext = extFrom(file.getContentType());
        String key = "avatars/users/" + user.getId() + "/" + UUID.randomUUID() + ext;
        String publicUrl = r2.putPublic(key, file.getBytes(), file.getContentType());

        // Retire the previous R2 object (ignores OAuth avatars from Google /
        // GitHub — those return null from keyFromPublicUrl).
        String previousKey = r2.keyFromPublicUrl(user.getAvatarUrl());
        if (previousKey != null) r2.deletePublic(previousKey);

        user.setAvatarUrl(publicUrl);
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("avatarUrl", publicUrl));
    }

    @Operation(summary = "Replace an org's logo (ADMIN only)")
    @PostMapping(value = "/api/orgs/{orgId}/avatar", consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<Map<String, String>> uploadOrgLogo(
            @PathVariable UUID orgId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        validate(file);
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        String ext = extFrom(file.getContentType());
        String key = "avatars/orgs/" + orgId + "/" + UUID.randomUUID() + ext;
        String publicUrl = r2.putPublic(key, file.getBytes(), file.getContentType());

        String previousKey = r2.keyFromPublicUrl(org.getLogoUrl());
        if (previousKey != null) r2.deletePublic(previousKey);

        org.setLogoUrl(publicUrl);
        orgRepo.save(org);
        return ResponseEntity.ok(Map.of("logoUrl", publicUrl));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("file exceeds the 3 MB limit");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED.contains(ct.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "content-type must be one of " + ALLOWED);
        }
    }

    private static String extFrom(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
