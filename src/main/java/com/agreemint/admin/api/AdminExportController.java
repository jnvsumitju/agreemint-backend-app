package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.StaffExport;
import com.agreemint.admin.repository.StaffExportRepository;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Staff-initiated exports (GDPR dumps + audit exports). A row lands with
 * status {@code PENDING}; an async worker (not yet written) claims it,
 * flips to {@code PROCESSING}, writes to S3, stamps the signed URL, and
 * flips to {@code READY}. The UI polls this endpoint to surface progress.
 */
@Tag(name = "Admin · Exports")
@RestController
@RequestMapping("/api/admin/exports")
public class AdminExportController {

    private static final Set<String> ALLOWED_SCOPES = Set.of("org", "user", "audit");

    private final StaffExportRepository repo;

    public AdminExportController(StaffExportRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<AdminDtos.ExportResponse> list() {
        return repo.findTop50ByOrderByRequestedAtDesc().stream()
                .map(AdminExportController::toDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<AdminDtos.ExportResponse> request(
            @RequestBody AdminDtos.ExportRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (req.scope() == null || !ALLOWED_SCOPES.contains(req.scope())) {
            return ResponseEntity.badRequest().build();
        }
        StaffExport e = new StaffExport();
        e.setId(UUID.randomUUID());
        e.setRequestedBy(principal.userId());
        e.setScope(req.scope());
        e.setTargetId(req.targetId());
        e.setStatus(StaffExport.Status.PENDING.name());
        repo.save(e);
        // TODO: enqueue the actual export job. For now the row sits in
        // PENDING until a scheduled worker picks it up.
        return ResponseEntity.ok(toDto(e));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminDtos.ExportResponse> status(@PathVariable UUID id) {
        Optional<StaffExport> maybe = repo.findById(id);
        return maybe.map(e -> ResponseEntity.ok(toDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static AdminDtos.ExportResponse toDto(StaffExport e) {
        return new AdminDtos.ExportResponse(
                e.getId(), e.getScope(), e.getTargetId(), e.getStatus(),
                e.getFileUrl(), e.getRequestedAt(), e.getCompletedAt());
    }
}
