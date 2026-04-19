package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.JwtService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ActivityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
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

    private final UserRepository userRepo;
    private final OrgMembershipRepository membershipRepo;
    private final JwtService jwtService;
    private final ActivityService activityService;

    public AdminImpersonationController(
            UserRepository userRepo,
            OrgMembershipRepository membershipRepo,
            JwtService jwtService,
            ActivityService activityService) {
        this.userRepo = userRepo;
        this.membershipRepo = membershipRepo;
        this.jwtService = jwtService;
        this.activityService = activityService;
    }

    @PostMapping
    public ResponseEntity<AdminDtos.ImpersonationResponse> start(
            @RequestBody AdminDtos.ImpersonationRequest req,
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
            if (m.isEmpty()) return ResponseEntity.badRequest().build();
            role = m.get().getRole();
        } else {
            Optional<OrgMembership> m = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(t.getId());
            if (m.isPresent()) {
                orgId = m.get().getOrganization().getId();
                role = m.get().getRole();
            }
        }

        int ttl = Math.max(1, Math.min(60, req.ttlMinutes() != null ? req.ttlMinutes() : 15));
        Duration ttlDur = Duration.ofMinutes(ttl);
        String token = jwtService.generateImpersonationToken(t, orgId, role, staff.userId(), ttlDur);
        Instant expires = Instant.now().plus(ttlDur);

        // Audit trail. Scoped to the target's org so it shows up alongside
        // their legitimate activity for forensic review.
        if (orgId != null) {
            activityService.log(
                    orgId, staff.userId(), staff.email(),
                    "impersonate.start", "User", t.getId(), t.getEmail());
        }

        return ResponseEntity.ok(new AdminDtos.ImpersonationResponse(
                token, expires, t.getId(), staff.userId()));
    }
}
