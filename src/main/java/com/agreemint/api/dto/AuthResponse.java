package com.agreemint.api.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user,
        OrgResponse org,
        String role,
        boolean requiresVerification
) {
    public AuthResponse(String accessToken, String refreshToken, UserResponse user,
                        OrgResponse org, String role) {
        this(accessToken, refreshToken, user, org, role, false);
    }
}
