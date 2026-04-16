package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record InviteMemberResponse(
        String status,
        OrgMembershipResponse membership,
        OrgInvitationResponse invitation
) {
    public static InviteMemberResponse added(OrgMembershipResponse m) {
        return new InviteMemberResponse("added", m, null);
    }

    public static InviteMemberResponse invited(OrgInvitationResponse inv) {
        return new InviteMemberResponse("invited", null, inv);
    }

    public record OrgInvitationResponse(
            UUID id,
            UUID orgId,
            String email,
            String role,
            Instant createdAt,
            Instant expiresAt
    ) {}
}
