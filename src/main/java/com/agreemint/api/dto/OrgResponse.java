package com.agreemint.api.dto;

import com.agreemint.domain.Organization;

import java.util.UUID;

public record OrgResponse(
        UUID id,
        String name,
        String slug,
        String logoUrl,
        String plan
) {
    public static OrgResponse from(Organization o) {
        return new OrgResponse(
                o.getId(), o.getName(), o.getSlug(),
                o.getLogoUrl(), o.getPlan().name()
        );
    }
}
