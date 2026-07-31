package com.agreemint.admin.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import com.agreemint.service.R2StorageService;
import com.agreemint.admin.service.StaffExportJob;
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
 * Staff-initiated exports (GDPR dumps + audit exports). A row lands with status
 * {@code PENDING}; {@code StaffExportJob} polls for it, flips it to
 * {@code PROCESSING}, writes JSON to R2 and flips it to {@code READY} or
 * {@code FAILED}. The UI polls {@link #status} to follow along.
 */
@Tag(name = "Admin · Exports")
@RestController
@RequestMapping("/api/admin/exports")
public class AdminExportController {

    private static final Set<String> ALLOWED_SCOPES = Set.of("org", "user", "audit");

    private final StaffExportRepository repo;
    private final R2StorageService r2;
    private final com.agreemint.service.ActivityService activityService;
    private final com.agreemint.repository.OrgMembershipRepository membershipRepo;
    private final com.agreemint.repository.UserRepository userRepo;
    private final com.agreemint.repository.OrganizationRepository orgRepo;
    private final com.agreemint.service.EmailService emailService;

    public AdminExportController(StaffExportRepository repo, R2StorageService r2,
            com.agreemint.service.ActivityService activityService,
            com.agreemint.repository.OrgMembershipRepository membershipRepo,
            com.agreemint.repository.UserRepository userRepo,
            com.agreemint.repository.OrganizationRepository orgRepo,
            com.agreemint.service.EmailService emailService) {
        this.repo = repo;
        this.r2 = r2;
        this.activityService = activityService;
        this.membershipRepo = membershipRepo;
        this.userRepo = userRepo;
        this.orgRepo = orgRepo;
        this.emailService = emailService;
    }

    @GetMapping
    public List<AdminDtos.ExportResponse> list() {
        return repo.findTop50ByOrderByRequestedAtDesc().stream()
                .map(AdminExportController::toDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<AdminDtos.ExportResponse> request(
            @jakarta.validation.Valid @RequestBody AdminDtos.ExportRequest req,
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
        audit(principal, "export.request", e);
        notifySubjects(e);
        // StaffExportJob polls for PENDING rows and processes them; the client
        // polls GET /{id} until status leaves PENDING.
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
                e.getFileUrl(),
                e.getError(), e.getRequestedAt(), e.getCompletedAt());
    }

    /**
     * Hand back a short-lived presigned URL as JSON.
     *
     * <p>Exists because {@link #download} cannot be used from the portal. That
     * endpoint 302s, and the redirect target is on R2's origin — a browser
     * cannot attach the staff bearer token to a plain navigation, and following
     * the redirect with {@code fetch} would need CORS on the bucket. Returning
     * the URL lets the portal authenticate here and then navigate straight to a
     * self-authenticating link.
     */
    @GetMapping("/{id}/download-url")
    public ResponseEntity<DownloadUrl> downloadUrl(@PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        StaffExport e = repo.findById(id).orElse(null);
        if (e == null) return ResponseEntity.notFound().build();
        if (!StaffExport.Status.READY.name().equals(e.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // Requesting an export and actually retrieving the bytes are different
        // acts, and only the second one means the data left the system. Both
        // are recorded.
        audit(principal, "export.download", e);
        return ResponseEntity.ok(new DownloadUrl(
                r2.presignDocumentGet(StaffExportJob.objectKey(id)).toString(),
                r2.presignTtlMinutes()));
    }

    /** @param expiresInMinutes how long {@code url} stays valid — shown to the operator */
    public record DownloadUrl(String url, int expiresInMinutes) {}

    /**
     * Record a staff export against every tenant whose data it contains.
     *
     * <p>An export is staff reading customer personal data, and the only record
     * of it used to be the {@code staff_exports} row — which the audit view does
     * not read, so nothing in the portal could answer "who exported whose data".
     *
     * <p>The row is written per affected tenant: the target org for a
     * {@code scope=org} export, and every org the subject belongs to for
     * {@code scope=user} — each of those tenants has a legitimate claim to the
     * record.
     *
     * <p>A {@code scope=audit} export spans every tenant and belongs to none, so
     * it is recorded once with a null org. That used to be impossible —
     * {@code activity_log.org_id} was NOT NULL — which meant the single broadest
     * export in the system was the one action the audit view could not show.
     * V22 relaxed the column for exactly this.
     */
    private void audit(UserPrincipal staff, String action, StaffExport e) {
        List<UUID> orgIds = switch (e.getScope()) {
            case "org" -> e.getTargetId() == null ? List.of() : List.of(e.getTargetId());
            case "user" -> e.getTargetId() == null ? List.of()
                    : membershipRepo.findByUserId(e.getTargetId()).stream()
                            .map(m -> m.getOrganization().getId())
                            .distinct()
                            .toList();
            default -> List.of();
        };

        if (orgIds.isEmpty()) {
            // Platform-wide, or a target that resolved to nothing. Recorded
            // once with no org rather than dropped: an unattributable action is
            // still an action, and this is the broadest one staff can take.
            activityService.log(null, staff.userId(), staff.email(),
                    action, "StaffExport", e.getId(), e.getScope());
            return;
        }
        for (UUID orgId : orgIds) {
            activityService.log(orgId, staff.userId(), staff.email(),
                    action, "StaffExport", e.getId(), e.getScope());
        }
    }

    /**
     * Tell the people whose data this export contains.
     *
     * <p>Same rule as impersonation: staff touching a customer's data is
     * something that customer gets told about, not something they could only
     * find out by asking. Who is told follows the scope — the subject for a
     * user export, the workspace admins for an org export, since a tenant-wide
     * export is a tenant-level event and mailing every member would be noise.
     *
     * <p>A {@code scope=audit} export spans every tenant and has no identifiable
     * subject, so nobody is notified. That is the same gap the audit row has,
     * and it is logged rather than papered over.
     */
    private void notifySubjects(StaffExport e) {
        String when = NOTICE_TIME.format(java.time.Instant.now());
        try {
            switch (e.getScope()) {
                case "user" -> {
                    if (e.getTargetId() == null) return;
                    userRepo.findById(e.getTargetId()).ifPresent(u -> {
                        String orgName = membershipRepo.findByUserId(u.getId()).stream()
                                .findFirst().map(m -> m.getOrganization().getName()).orElse(null);
                        emailService.sendDataExportNoticeEmail(
                                u.getEmail(), orgName, when, "your account");
                    });
                }
                case "org" -> {
                    if (e.getTargetId() == null) return;
                    String orgName = orgRepo.findById(e.getTargetId())
                            .map(o -> o.getName()).orElse(null);
                    membershipRepo.findByOrganizationId(e.getTargetId()).stream()
                            .filter(m -> m.getRole() == com.agreemint.domain.OrgRole.ADMIN)
                            .forEach(m -> emailService.sendDataExportNoticeEmail(
                                    m.getUser().getEmail(), orgName, when, "your whole workspace"));
                }
                // scope=audit covers every tenant, so there is no individual
                // subject to notify — the recipient list would be the entire
                // user table, which is not a notification, it is a broadcast.
                // The org-less audit row above is the record for this one, and
                // it is visible to staff oversight in the audit view.
                default -> log.info("Staff export {} scope={} is platform-wide; "
                        + "recorded in the audit log, no individual subject to notify",
                        e.getId(), e.getScope());
            }
        } catch (RuntimeException mailFailure) {
            // Never fails the export — the audit rows are the durable record.
            log.warn("Could not notify subjects of export {}: {}", e.getId(), mailFailure.getMessage());
        }
    }

    private static final java.time.format.DateTimeFormatter NOTICE_TIME =
            java.time.format.DateTimeFormatter
                    .ofPattern("d MMM yyyy, HH:mm 'UTC'")
                    .withZone(java.time.ZoneOffset.UTC);

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AdminExportController.class);

    /**
     * Download a finished export.
     *
     * <p>302 to a short-lived presigned URL, matching how generated documents
     * are served — the object lives in the private bucket and is never public.
     * Convenient from curl; see {@link #downloadUrl} for the browser path.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Void> download(@PathVariable UUID id) {
        StaffExport e = repo.findById(id).orElse(null);
        if (e == null) return ResponseEntity.notFound().build();
        if (!StaffExport.Status.READY.name().equals(e.getStatus())) {
            // Not an error — the caller polled too early.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        java.net.URL presigned = r2.presignDocumentGet(StaffExportJob.objectKey(id));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, presigned.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }

}
