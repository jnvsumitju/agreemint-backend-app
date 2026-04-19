package com.agreemint.api.dto;

import com.agreemint.domain.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String name,
        String avatarUrl,
        String provider,
        Instant createdAt,
        /** Internal Agreemint staff — clients use this to gate admin-portal UI. */
        boolean isStaff
) {
    public static UserResponse from(User u) {
        return new UserResponse(
                u.getId(), u.getEmail(), u.getName(),
                u.getAvatarUrl(), u.getProvider().name(), u.getCreatedAt(),
                u.isStaff()
        );
    }
}
