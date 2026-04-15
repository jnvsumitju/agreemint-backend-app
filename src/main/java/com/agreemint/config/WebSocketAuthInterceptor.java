package com.agreemint.config;

import com.agreemint.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public WebSocketAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            List<String> authHeaders = accessor.getNativeHeader("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                String authHeader = authHeaders.get(0);
                if (authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    Claims claims = jwtService.extractClaimsOrNull(token);
                    if (claims != null) {
                        String userId = claims.getSubject();
                        String email = claims.get("email", String.class);
                        String name = claims.get("name", String.class);

                        WebSocketPrincipal principal = new WebSocketPrincipal(
                                UUID.fromString(userId), name, email
                        );
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
                        accessor.setUser(authentication);
                    }
                }
            }
        }

        return message;
    }

    /** Simple principal carrying userId, name, and email for WebSocket sessions. */
    public static class WebSocketPrincipal implements Principal {

        private final UUID userId;
        private final String displayName;
        private final String email;

        public WebSocketPrincipal(UUID userId, String displayName, String email) {
            this.userId = userId;
            this.displayName = displayName;
            this.email = email;
        }

        @Override
        public String getName() {
            return userId.toString();
        }

        public UUID getUserId() {
            return userId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEmail() {
            return email;
        }
    }
}
