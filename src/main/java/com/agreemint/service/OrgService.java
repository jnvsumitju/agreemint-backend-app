package com.agreemint.service;

import com.agreemint.api.dto.OrgMembershipResponse;
import com.agreemint.api.dto.OrgResponse;
import com.agreemint.domain.*;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.OrgAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrgService {

    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final UserRepository userRepo;
    private final OrgAuthorizationService authz;
    private final SlugService slugService;

    public OrgService(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            UserRepository userRepo,
            OrgAuthorizationService authz,
            SlugService slugService
    ) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.userRepo = userRepo;
        this.authz = authz;
        this.slugService = slugService;
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
    public OrgMembershipResponse inviteMember(UUID actorId, UUID orgId, String email, OrgRole role) {
        authz.assertRole(actorId, orgId, OrgRole.ADMIN);
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        User invitee = userRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with email: " + email));

        if (membershipRepo.existsByUserIdAndOrganizationId(invitee.getId(), orgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of this organization");
        }

        OrgMembership membership = new OrgMembership();
        membership.setUser(invitee);
        membership.setOrganization(org);
        membership.setRole(role != null ? role : OrgRole.VIEWER);
        membership = membershipRepo.save(membership);

        return OrgMembershipResponse.from(membership);
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

}
