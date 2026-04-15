package com.agreemint.websocket;

import java.util.List;

public record PresenceMessage(List<UserPresenceDto> users) {

    public record UserPresenceDto(
            String userId,
            String name,
            String email,
            String color,
            String connectedAt
    ) {
    }
}
