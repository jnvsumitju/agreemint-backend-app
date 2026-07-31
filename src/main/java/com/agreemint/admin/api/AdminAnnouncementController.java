package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.Announcement;
import com.agreemint.admin.repository.AnnouncementRepository;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for in-app announcement banners. {@code targetOrgIds} is stored as
 * a CSV string on disk (small set, not worth a join table) and serialised
 * back to a UUID list over the wire.
 */
@Tag(name = "Admin · Announcements")
@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementRepository repo;

    public AdminAnnouncementController(AnnouncementRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<AdminDtos.AnnouncementResponse> list() {
        return repo.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(AdminAnnouncementController::toDto)
                .toList();
    }

    @PostMapping
    public AdminDtos.AnnouncementResponse create(
            @jakarta.validation.Valid @RequestBody AdminDtos.AnnouncementRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        Announcement a = new Announcement();
        a.setId(UUID.randomUUID());
        a.setCreatedBy(principal.userId());
        apply(a, req);
        repo.save(a);
        return toDto(a);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminDtos.AnnouncementResponse> update(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody AdminDtos.AnnouncementRequest req) {
        Optional<Announcement> maybe = repo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        Announcement a = maybe.get();
        apply(a, req);
        a.setUpdatedAt(Instant.now());
        repo.save(a);
        return ResponseEntity.ok(toDto(a));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static void apply(Announcement a, AdminDtos.AnnouncementRequest req) {
        a.setTitle(req.title());
        a.setBody(req.body());
        a.setSeverity(req.severity() != null ? req.severity() : "info");
        a.setTargetOrgIds(req.targetOrgIds() == null || req.targetOrgIds().isEmpty()
                ? null
                : req.targetOrgIds().stream().map(UUID::toString).collect(Collectors.joining(",")));
        a.setStartsAt(req.startsAt());
        a.setEndsAt(req.endsAt());
        a.setActive(req.active());
    }

    private static AdminDtos.AnnouncementResponse toDto(Announcement a) {
        List<UUID> ids = a.getTargetOrgIds() == null
                ? List.of()
                : Arrays.stream(a.getTargetOrgIds().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(UUID::fromString).toList();
        return new AdminDtos.AnnouncementResponse(
                a.getId(), a.getTitle(), a.getBody(), a.getSeverity(),
                ids, a.isActive(), a.getStartsAt(), a.getEndsAt(),
                a.getCreatedAt(), a.getCreatedBy());
    }
}
