package com.agreemint.admin.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.agreemint.admin.service.ImpersonationSessionService;
import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.JwtService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ActivityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue a short-lived impersonation JWT so a staff user can log into the
 * main app "as" a target user for debugging / support. The token carries
 * an {@code impersonatedBy} claim so downstream actions stay attributable
 * to the staff member who kicked it off.
 */
@Tag(name = "Admin · Impersonation")
@RestController
@RequestMapping("/api/admin/impersonate")
public class AdminImpersonationController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AdminImpersonationController.class);

    private final UserRepository userRepo;
    private final OrgMembershipRepository membershipRepo;
    private final OrganizationRepository orgRepo;
    private final JwtService jwtService;
    private final ActivityService activityService;
    private final ImpersonationSessionService sessions;
    private final com.agreemint.service.EmailService emailService;

    /** UTC, spelled out — the reader is a customer, not an operator. */
    private static final java.time.format.DateTimeFormatter NOTICE_TIME =
            java.time.format.DateTimeFormatter
                    .ofPattern("d MMM yyyy, HH:mm 'UTC'")
                    .withZone(java.time.ZoneOffset.UTC);

    public AdminImpersonationController(
            UserRepository userRepo,
            OrgMembershipRepository membershipRepo,
            OrganizationRepository orgRepo,
            JwtService jwtService,
            ActivityService activityService,
            ImpersonationSessionService sessions,
            com.agreemint.service.EmailService emailService) {
        this.userRepo = userRepo;
        this.membershipRepo = membershipRepo;
        this.orgRepo = orgRepo;
        this.jwtService = jwtService;
        this.activityService = activityService;
        this.sessions = sessions;
        this.emailService = emailService;
    }

    @PostMapping
    public ResponseEntity<AdminDtos.ImpersonationResponse> start(
            @jakarta.validation.Valid @RequestBody AdminDtos.ImpersonationRequest req,
            @AuthenticationPrincipal UserPrincipal staff) {
        Optional<User> target = userRepo.findById(req.targetUserId());
        if (target.isEmpty()) return ResponseEntity.notFound().build();
        User t = target.get();

        // Resolve the target org — either the caller-supplied one (must be a
        // membership the target actually has) or their first membership.
        UUID orgId = req.targetOrgId();
        OrgRole role = null;
        if (orgId != null) {
            Optional<OrgMembership> m = membershipRepo.findByUserIdAndOrganizationId(t.getId(), orgId);
            if (m.isEmpty()) {
                // Distinct message from the no-org case below: the two failures
                // have different remedies (reload vs give up), and a bare 400
                // left the operator unable to tell them apart.
                throw new com.agreemint.api.BadRequestException(
                        t.getEmail() + " is not a member of that workspace. The list may be"
                                + " stale — reload this page and try again.");
            }
            role = m.get().getRole();
        } else {
            Optional<OrgMembership> m = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(t.getId());
            if (m.isPresent()) {
                orgId = m.get().getOrganization().getId();
                role = m.get().getRole();
            }
        }

        // A session needs a workspace to open in: JwtService mints the token
        // against one, the X-Org-Id pin keeps the session inside it, and the
        // audit rows name it. An org-less account has nothing to scope to and
        // nothing in it to look at.
        //
        // (The original reason was that activity_log.org_id was NOT NULL so the
        // session could not be recorded. V22 relaxed that, but the scoping
        // reason stands on its own and is the one that matters.)
        if (orgId == null) {
            throw new com.agreemint.api.BadRequestException(
                    t.getEmail() + " belongs to no workspace, so a support session cannot be"
                            + " opened or recorded. Add them to a workspace first.");
        }

        int ttl = Math.max(1, Math.min(60, req.ttlMinutes() != null ? req.ttlMinutes() : 15));
        Duration ttlDur = Duration.ofMinutes(ttl);
        // Register the session before minting: the auth filter refuses any
        // impersonation token whose session is not live, so this is what makes
        // the token usable — and what makes it revocable.
        String sessionId = UUID.randomUUID().toString();
        // orgId is non-null past the guard above.
        String orgName = orgRepo.findById(orgId).map(o -> o.getName()).orElse(null);
        sessions.register(new ImpersonationSessionService.Session(
                sessionId, staff.userId(), staff.email(),
                t.getId(), t.getEmail(), orgId, orgName,
                Instant.now(), null), ttlDur);

        String token = jwtService.generateImpersonationToken(
                t, orgId, role, staff.userId(), ttlDur, sessionId);
        Instant expires = Instant.now().plus(ttlDur);

        // Audit trail. Scoped to the target's org so it shows up alongside
        // their legitimate activity for forensic review.
        activityService.log(
                orgId, staff.userId(), staff.email(),
                "impersonate.start", "User", t.getId(), t.getEmail());

        // The person whose account this is gets told. The audit trail is
        // staff-only, so without this they had no way to learn it happened.
        // Best-effort: @Async, and a mail failure must not fail the session —
        // the activity_log row above is the durable record.
        try {
            emailService.sendImpersonationNoticeEmail(
                    t.getEmail(), orgName, NOTICE_TIME.format(Instant.now()), ttl);
        } catch (RuntimeException mailFailure) {
            log.warn("Could not notify {} of impersonation: {}",
                    t.getEmail(), mailFailure.getMessage());
        }

        return ResponseEntity.ok(new AdminDtos.ImpersonationResponse(
                token, expires, t.getId(), staff.userId(), sessionId));
    }

    /**
     * Every impersonation session that is currently live.
     *
     * <p>The revoke path below is deliberately open to any staff member, but
     * without this it was reachable only by whoever still had the session id on
     * screen — the one person least likely to want to end it. This is what makes
     * "anyone can kill a session" true rather than nominal.
     */
    @GetMapping("/sessions")
    public List<ImpersonationSessionService.Session> live() {
        return sessions.listLive();
    }

    /**
     * End an impersonation session immediately.
     *
     * <p>Without this a session could only be waited out. Callable by any staff
     * member, not just the one who started it — if a session needs killing, the
     * person who can do it should not have to be the person who opened it.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable String sessionId,
                                        @AuthenticationPrincipal UserPrincipal staff) {
        ImpersonationSessionService.Session ended = sessions.revoke(sessionId);
        if (ended == null) {
            // Already expired or never existed — nothing to end.
            return ResponseEntity.notFound().build();
        }

        // Closes the loop opened by impersonate.start, in the same org so the
        // two events sit together when the session is reviewed later.
        if (ended.orgId() != null) {
            activityService.log(
                    ended.orgId(), staff.userId(), staff.email(),
                    "impersonate.end", "User", ended.targetUserId(), ended.targetEmail());
        }
        log.info("Impersonation session {} revoked by {} (started by {})",
                sessionId, staff.email(), ended.operatorEmail());
        return ResponseEntity.noContent().build();
    }
}
