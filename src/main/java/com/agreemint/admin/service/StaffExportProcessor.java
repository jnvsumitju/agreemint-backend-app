package com.agreemint.admin.service;

import com.agreemint.admin.domain.StaffExport;
import com.agreemint.admin.repository.StaffExportRepository;
import com.agreemint.domain.*;
import com.agreemint.repository.*;
import com.agreemint.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds and stores one staff export.
 *
 * <p>A separate bean from {@link StaffExportJob} on purpose. The payload walks
 * lazy associations ({@code OrgMembership.user} / {@code .organization} are both
 * LAZY), so it must run inside a transaction — and Spring only applies
 * {@code @Transactional} through its proxy, which a same-class call would skip.
 *
 * <p>Output is JSON in the private documents bucket. Credentials are never
 * included: no password hashes, no API key hashes.
 */
@Service
public class StaffExportProcessor {

    private static final Logger log = LoggerFactory.getLogger(StaffExportProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on rows in a single export, so one job cannot run away. */
    private static final int MAX_ROWS = 10_000;

    private final StaffExportRepository exportRepo;
    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final OrgMembershipRepository membershipRepo;
    private final TemplateRepository templateRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ActivityLogRepository auditRepo;
    private final R2StorageService r2;

    public StaffExportProcessor(StaffExportRepository exportRepo,
                                 OrganizationRepository orgRepo,
                                 UserRepository userRepo,
                                 OrgMembershipRepository membershipRepo,
                                 TemplateRepository templateRepo,
                                 ApiKeyRepository apiKeyRepo,
                                 ActivityLogRepository auditRepo,
                                 R2StorageService r2) {
        this.exportRepo = exportRepo;
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.membershipRepo = membershipRepo;
        this.templateRepo = templateRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.auditRepo = auditRepo;
        this.r2 = r2;
    }

    @Transactional
    public void process(UUID exportId) {
        StaffExport export = exportRepo.findById(exportId).orElse(null);
        if (export == null) return;

        try {
            byte[] payload = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(build(export))
                    ;
            r2.putDocument(StaffExportJob.objectKey(exportId), payload, "application/json");

            export.setStatus(StaffExport.Status.READY.name());
            export.setFileUrl("/api/admin/exports/" + exportId + "/file");
            export.setError(null);
            export.setCompletedAt(Instant.now());
            log.info("Staff export {} ready scope={} bytes={}",
                    exportId, export.getScope(), payload.length);
        } catch (Exception e) {
            // Deliberately rethrown rather than recorded here. If the cause came
            // from a repository call, Spring has already marked this transaction
            // rollback-only, so writing FAILED and `error` inside it is silently
            // discarded at commit and the row is left PROCESSING with no reason.
            // The caller records the failure in a fresh transaction instead.
            log.error("Staff export {} failed scope={}", exportId, export.getScope(), e);
            throw new IllegalStateException(
                    truncate(e.getClass().getSimpleName() + ": " + e.getMessage()), e);
        }
        exportRepo.save(export);
    }

    /**
     * Record a failure in its own transaction.
     *
     * <p>REQUIRES_NEW because the transaction that failed may be rollback-only;
     * joining it would throw the write away. Called from
     * {@code StaffExportJob} — an external call, so the proxy applies.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID exportId, String reason) {
        StaffExport export = exportRepo.findById(exportId).orElse(null);
        if (export == null) return;
        export.setStatus(StaffExport.Status.FAILED.name());
        export.setError(truncate(reason));
        export.setCompletedAt(Instant.now());
        exportRepo.save(export);
    }

    // ── Payload builders ─────────────────────────────────────────────────────

    private ObjectNode build(StaffExport export) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("exportId", export.getId().toString());
        root.put("scope", export.getScope());
        root.put("generatedAt", Instant.now().toString());
        if (export.getTargetId() != null) {
            root.put("targetId", export.getTargetId().toString());
        }

        switch (export.getScope()) {
            case "org" -> buildOrg(root, export.getTargetId());
            case "user" -> buildUser(root, export.getTargetId());
            case "audit" -> buildAudit(root, export.getTargetId());
            default -> throw new IllegalArgumentException("Unknown export scope: " + export.getScope());
        }
        return root;
    }

    private void buildOrg(ObjectNode root, UUID orgId) {
        Organization org = requireTarget(orgRepo.findById(orgId).orElse(null), "Organisation", orgId);

        ObjectNode o = root.putObject("organization");
        o.put("id", org.getId().toString());
        o.put("name", org.getName());
        o.put("slug", org.getSlug());
        o.put("plan", org.getPlan() == null ? "FREE" : org.getPlan().name());
        o.put("createdAt", String.valueOf(org.getCreatedAt()));

        ArrayNode members = root.putArray("members");
        for (OrgMembership m : membershipRepo.findByOrganizationId(orgId)) {
            ObjectNode n = members.addObject();
            if (m.getUser() != null) {
                n.put("userId", String.valueOf(m.getUser().getId()));
                n.put("email", m.getUser().getEmail());
                n.put("name", m.getUser().getName());
            }
            n.put("role", String.valueOf(m.getRole()));
        }

        ArrayNode templates = root.putArray("templates");
        for (Template t : templateRepo.findByOrgIdOrderByCreatedAtDesc(orgId)) {
            ObjectNode n = templates.addObject();
            n.put("id", t.getId().toString());
            n.put("name", t.getName());
            n.put("createdAt", String.valueOf(t.getCreatedAt()));
        }

        // API keys: metadata only. The stored hash is a credential — it must
        // never leave the database, let alone land in a downloadable file.
        ArrayNode keys = root.putArray("apiKeys");
        for (ApiKey k : apiKeyRepo.findByOrgIdOrderByCreatedAtDesc(orgId)) {
            ObjectNode n = keys.addObject();
            n.put("id", k.getId().toString());
            n.put("name", k.getName());
            n.put("prefix", k.getKeyPrefix());
            n.put("last4", k.getKeyLast4());
            n.put("createdAt", String.valueOf(k.getCreatedAt()));
            n.put("revoked", k.getRevokedAt() != null);
        }
    }

    private void buildUser(ObjectNode root, UUID userId) {
        User user = requireTarget(userRepo.findById(userId).orElse(null), "User", userId);

        // Password hash deliberately omitted.
        ObjectNode u = root.putObject("user");
        u.put("id", user.getId().toString());
        u.put("email", user.getEmail());
        u.put("name", user.getName());
        u.put("emailVerified", user.isEmailVerified());
        u.put("staff", user.isStaff());
        u.put("createdAt", String.valueOf(user.getCreatedAt()));

        ArrayNode orgs = root.putArray("organizations");
        for (OrgMembership m : membershipRepo.findByUserId(userId)) {
            ObjectNode n = orgs.addObject();
            if (m.getOrganization() != null) {
                n.put("orgId", String.valueOf(m.getOrganization().getId()));
                n.put("orgName", m.getOrganization().getName());
            }
            n.put("role", String.valueOf(m.getRole()));
        }
    }

    private void buildAudit(ObjectNode root, UUID orgId) {
        // A null target means the whole platform; otherwise scope to one org.
        var page = auditRepo.search(orgId, null, null,
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")));

        root.put("eventCount", page.getNumberOfElements());
        root.put("truncated", page.getTotalElements() > MAX_ROWS);

        ArrayNode events = root.putArray("events");
        for (ActivityLog e : page.getContent()) {
            ObjectNode n = events.addObject();
            n.put("id", e.getId().toString());
            n.put("orgId", String.valueOf(e.getOrgId()));
            n.put("userId", String.valueOf(e.getUserId()));
            n.put("userName", e.getUserName());
            n.put("action", e.getAction());
            n.put("entityType", e.getEntityType());
            n.put("entityName", e.getEntityName());
            n.put("createdAt", String.valueOf(e.getCreatedAt()));
        }
    }

    private static <T> T requireTarget(T value, String label, UUID id) {
        if (value == null) {
            throw new IllegalArgumentException(label + " not found: " + id);
        }
        return value;
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "…";
    }
}
