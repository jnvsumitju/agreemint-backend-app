package com.agreemint.api.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user,
        OrgResponse org,
        String role
) {}
