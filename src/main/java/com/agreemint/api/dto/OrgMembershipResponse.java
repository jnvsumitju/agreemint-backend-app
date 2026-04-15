package com.agreemint.api.dto;

import com.agreemint.domain.OrgMembership;

import java.time.Instant;
import java.util.UUID;

public record OrgMembershipResponse(
        UUID id,
        UUID userId,
        UUID orgId,
        String role,
        String userName,
        String userEmail,
        String userAvatar,
        Instant createdAt
) {
    public static OrgMembershipResponse from(OrgMembership m) {
        var u = m.getUser();
        return new OrgMembershipResponse(
                m.getId(), u.getId(), m.getOrganization().getId(),
                m.getRole().name(), u.getName(), u.getEmail(),
                u.getAvatarUrl(), m.getCreatedAt()
        );
    }
}
