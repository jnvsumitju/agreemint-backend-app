package com.agreemint.api;

import com.agreemint.admin.domain.Announcement;
import com.agreemint.admin.repository.AnnouncementRepository;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * End-user read surface for announcements authored in the admin portal.
 * Returns every announcement that's currently "active" — flag on, within
 * its start/end window (when set), and either global or targeted at the
 * caller's current org.
 *
 * Writes are not exposed here; those live under /api/admin/announcements
 * and are gated on ROLE_STAFF.
 */
@Tag(name = "Announcements")
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementRepository repo;

    public AnnouncementController(AnnouncementRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/active")
    public List<PublicAnnouncement> listActive(@AuthenticationPrincipal UserPrincipal principal) {
        Instant now = Instant.now();
        UUID orgId = principal != null ? principal.orgId() : null;
        return repo.findByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(a -> isInWindow(a, now))
                .filter(a -> isTargeted(a, orgId))
                .map(AnnouncementController::toPublic)
                .toList();
    }

    private static boolean isInWindow(Announcement a, Instant now) {
        if (a.getStartsAt() != null && now.isBefore(a.getStartsAt())) return false;
        if (a.getEndsAt() != null && now.isAfter(a.getEndsAt())) return false;
        return true;
    }

    private static boolean isTargeted(Announcement a, UUID callerOrgId) {
        if (a.getTargetOrgIds() == null || a.getTargetOrgIds().isBlank()) return true; // global
        if (callerOrgId == null) return false;
        // CSV of UUIDs — small set, substring match is fine.
        return Arrays.stream(a.getTargetOrgIds().split(","))
                .map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(callerOrgId.toString()));
    }

    private static PublicAnnouncement toPublic(Announcement a) {
        return new PublicAnnouncement(
                a.getId(),
                a.getTitle(),
                a.getBody(),
                a.getSeverity(),
                a.getStartsAt(),
                a.getEndsAt());
    }

    /** Tight wire format — end users don't need the targeting / authorship fields. */
    public record PublicAnnouncement(
            UUID id,
            String title,
            String body,
            String severity,
            Instant startsAt,
            Instant endsAt
    ) {}
}
