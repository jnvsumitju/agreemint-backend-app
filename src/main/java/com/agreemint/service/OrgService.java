package com.agreemint.service;

import com.agreemint.repository.TemplateRepository;
import com.agreemint.config.FreePlanProperties;
import com.agreemint.api.dto.OrgEntitlementsResponse;
import com.agreemint.billing.PlanGate;
import com.agreemint.api.dto.InviteMemberResponse;
import com.agreemint.api.dto.OrgMembershipResponse;
import com.agreemint.api.dto.OrgResponse;
import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.*;
import com.agreemint.repository.OrgInvitationRepository;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.OrgAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrgService {

    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final OrgInvitationRepository invitationRepo;
    private final UserRepository userRepo;
    private final OrgAuthorizationService authz;
    private final SlugService slugService;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;
    private final PlanGate planGate;
    private final FreePlanProperties freeLimits;
    private final TemplateRepository templateRepo;

    public OrgService(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            OrgInvitationRepository invitationRepo,
            UserRepository userRepo,
            OrgAuthorizationService authz,
            SlugService slugService,
            EmailService emailService,
            FrontendProperties frontendProps
    ,
            PlanGate planGate,
            FreePlanProperties freeLimits,
            TemplateRepository templateRepo) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.invitationRepo = invitationRepo;
        this.userRepo = userRepo;
        this.authz = authz;
        this.slugService = slugService;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
        this.planGate = planGate;
        this.freeLimits = freeLimits;
        this.templateRepo = templateRepo;
    }

    @Transactional(readOnly = true)
    public List<OrgResponse> listUserOrgs(UUID userId) {
        return membershipRepo.findWithOrgByUserId(userId).stream()
                .map(m -> OrgResponse.from(m.getOrganization()))
                .toList();
    }

    @Transactional
    public OrgResponse createOrg(UUID userId, String name) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Free-plan workspace ceiling. Counts only workspaces this user
        // administers — belonging to someone else's workspace does not consume
        // your own allowance. No-op unless the free-plan cutover is configured.
        List<OrgMembership> owned = membershipRepo.findByUserId(userId).stream()
                .filter(m -> m.getRole() == OrgRole.ADMIN)
                .toList();
        boolean anyOwnedIsPaid = owned.stream()
                .map(OrgMembership::getOrganization)
                .anyMatch(o -> o != null && o.getPlan() != null && o.getPlan().isPaid());
        java.time.Instant oldestOwnedAt = owned.stream()
                .map(OrgMembership::getOrganization)
                .filter(java.util.Objects::nonNull)
                .map(Organization::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .min(java.time.Instant::compareTo)
                .orElse(null);
        planGate.requireWorkspaceHeadroom(owned.size(), anyOwnedIsPaid, oldestOwnedAt);

        String slug = slugService.generateUniqueSlug(name);
        Organization org = new Organization();
        org.setName(name.trim());
        org.setSlug(slug);
        org.setPlan(OrgPlan.FREE);
        org = orgRepo.save(org);

        OrgMembership membership = new OrgMembership();
        membership.setUser(user);
        membership.setOrganization(org);
        membership.setRole(OrgRole.ADMIN);
        membershipRepo.save(membership);

        return OrgResponse.from(org);
    }

    @Transactional(readOnly = true)
    public OrgResponse getOrg(UUID orgId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        return OrgResponse.from(org);
    }

    @Transactional
    public OrgResponse updateOrg(UUID userId, UUID orgId, String name, String logoUrl) {
        authz.assertRole(userId, orgId, OrgRole.ADMIN);
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (name != null) org.setName(name.trim());
        if (logoUrl != null) org.setLogoUrl(logoUrl.trim());
        org.setUpdatedAt(Instant.now());
        return OrgResponse.from(orgRepo.save(org));
    }

    @Transactional(readOnly = true)
    public List<OrgMembershipResponse> listMembers(UUID userId, UUID orgId) {
        authz.assertRole(userId, orgId, OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return membershipRepo.findByOrganizationId(orgId).stream()
                .map(OrgMembershipResponse::from)
                .toList();
    }

    @Transactional
    public InviteMemberResponse inviteMember(UUID actorId, UUID orgId, String email, OrgRole role) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String normalizedEmail = email.toLowerCase().trim();
        OrgRole assignedRole = role != null ? role : OrgRole.VIEWER;

        // If user already registered, add directly
        Optional<User> existingUser = userRepo.findByEmail(normalizedEmail);
        if (existingUser.isPresent()) {
            User invitee = existingUser.get();
            if (membershipRepo.existsByUserIdAndOrganizationId(invitee.getId(), orgId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of this organization");
            }
            OrgMembership membership = new OrgMembership();
            membership.setUser(invitee);
            membership.setOrganization(org);
            membership.setRole(assignedRole);
            membership = membershipRepo.save(membership);
            return InviteMemberResponse.added(OrgMembershipResponse.from(membership));
        }

        // User not registered — create pending invitation
        if (invitationRepo.existsByOrgIdAndEmailAndAcceptedAtIsNull(orgId, normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An invitation has already been sent to this email");
        }

        User actor = userRepo.findById(actorId).orElse(null);
        String actorName = actor != null ? actor.getName() : "A team member";

        OrgInvitation invitation = new OrgInvitation();
        invitation.setOrgId(orgId);
        invitation.setEmail(normalizedEmail);
        invitation.setRole(assignedRole);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setInvitedBy(actorId);
        invitation.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        invitationRepo.save(invitation);

        String inviteLink = frontendProps.getBaseUrl() + "/register?invite=" + invitation.getToken();
        emailService.sendOrgInviteEmail(normalizedEmail, org.getName(), actorName, assignedRole.name(), inviteLink);

        return InviteMemberResponse.invited(new InviteMemberResponse.OrgInvitationResponse(
                invitation.getId(), orgId, normalizedEmail, assignedRole.name(),
                invitation.getCreatedAt(), invitation.getExpiresAt()
        ));
    }

    @Transactional(readOnly = true)
    public List<InviteMemberResponse.OrgInvitationResponse> listPendingInvitations(UUID actorId, UUID orgId) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        return invitationRepo.findByOrgIdAndAcceptedAtIsNull(orgId).stream()
                .filter(OrgInvitation::isPending)
                .map(inv -> new InviteMemberResponse.OrgInvitationResponse(
                        inv.getId(), inv.getOrgId(), inv.getEmail(), inv.getRole().name(),
                        inv.getCreatedAt(), inv.getExpiresAt()
                ))
                .toList();
    }

    @Transactional
    public void cancelInvitation(UUID actorId, UUID orgId, UUID invitationId) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        OrgInvitation inv = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!inv.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        invitationRepo.delete(inv);
    }

    /** Accept all pending invitations for a newly registered user. Called from AuthService. */
    @Transactional
    public void acceptPendingInvitations(User user) {
        List<OrgInvitation> pending = invitationRepo.findByEmailAndAcceptedAtIsNull(user.getEmail());
        for (OrgInvitation inv : pending) {
            if (inv.isExpired()) continue;
            if (membershipRepo.existsByUserIdAndOrganizationId(user.getId(), inv.getOrgId())) continue;

            Organization org = orgRepo.findById(inv.getOrgId()).orElse(null);
            if (org == null) continue;

            OrgMembership membership = new OrgMembership();
            membership.setUser(user);
            membership.setOrganization(org);
            membership.setRole(inv.getRole());
            membershipRepo.save(membership);

            inv.setAcceptedAt(Instant.now());
            invitationRepo.save(inv);
        }
    }

    @Transactional
    public OrgMembershipResponse changeRole(UUID actorId, UUID orgId, UUID membershipId, OrgRole newRole) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        OrgMembership membership = membershipRepo.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!membership.getOrganization().getId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        membership.setRole(newRole);
        return OrgMembershipResponse.from(membershipRepo.save(membership));
    }

    @Transactional
    public void removeMember(UUID actorId, UUID orgId, UUID membershipId) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        OrgMembership membership = membershipRepo.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!membership.getOrganization().getId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        // Prevent removing the last admin (pessimistic lock to avoid race condition)
        if (membership.getRole() == OrgRole.ADMIN) {
            List<OrgMembership> admins = membershipRepo.findAdminsForUpdate(orgId);
            if (admins.size() <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove the last admin");
            }
        }

        membershipRepo.delete(membership);
    }


    /** Resolved limits for the console. See OrgEntitlementsResponse. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public OrgEntitlementsResponse entitlements(UUID userId, UUID orgId) {
        // Any member may read this; non-members must not learn another
        // workspace's plan or limits.
        membershipRepo.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Not a member of this organization"));

        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

        boolean restricted = planGate.isFreeRestricted(orgId);
        return new OrgEntitlementsResponse(
                org.getPlan() == null ? "FREE" : org.getPlan().name(),
                restricted,
                restricted ? freeLimits.getMaxTemplates() : 0,
                templateRepo.countByOrgId(orgId),
                restricted ? freeLimits.getMaxWorkspaces() : 0);
    }

}
