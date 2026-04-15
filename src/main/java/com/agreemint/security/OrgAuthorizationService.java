package com.agreemint.security;

import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Template;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.TemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.UUID;

/**
 * Checks whether a user has the required role within an organization.
 * Throws 403 Forbidden if the check fails.
 */
@Service
public class OrgAuthorizationService {

    private final OrgMembershipRepository membershipRepo;
    private final TemplateRepository templateRepo;

    public OrgAuthorizationService(OrgMembershipRepository membershipRepo, TemplateRepository templateRepo) {
        this.membershipRepo = membershipRepo;
        this.templateRepo = templateRepo;
    }

    /**
     * Assert the user holds one of the allowed roles in the given org.
     * @throws ResponseStatusException 403 if not authorized, 404 if not a member
     */
    public OrgRole assertRole(UUID userId, UUID orgId, OrgRole... allowed) {
        if (orgId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No organization context");
        }
        OrgMembership membership = membershipRepo.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this organization"));

        OrgRole actual = membership.getRole();
        if (Arrays.stream(allowed).noneMatch(r -> r == actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient permissions. Required: " + Arrays.toString(allowed) + ", actual: " + actual);
        }
        return actual;
    }

    /**
     * Assert the user can access a template at one of the allowed role levels.
     * Resolves the template's org, then delegates to {@link #assertRole}.
     */
    public OrgRole assertTemplateAccess(UUID userId, UUID templateId, OrgRole... allowed) {
        Template template = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));

        UUID orgId = template.getOrgId();
        if (orgId == null) {
            // Legacy unowned template — allow access (backward compat)
            return OrgRole.ADMIN;
        }
        return assertRole(userId, orgId, allowed);
    }

    /** Resolve the user's role for a given org, or null if not a member. */
    public OrgRole resolveRole(UUID userId, UUID orgId) {
        if (orgId == null) return null;
        return membershipRepo.findByUserIdAndOrganizationId(userId, orgId)
                .map(OrgMembership::getRole)
                .orElse(null);
    }
}
