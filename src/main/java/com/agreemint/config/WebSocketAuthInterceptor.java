package com.agreemint.config;

import com.agreemint.admin.service.ImpersonationSessionService;
import com.agreemint.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP authentication for the collab channel.
 *
 * <p>{@code SecurityConfig} marks {@code /ws/**} permitAll because auth happens
 * here instead — which means {@link com.agreemint.security.JwtAuthenticationFilter}
 * never runs for this transport, and every rule it enforces has to be repeated
 * here or it simply does not exist on this path.
 *
 * <p>That is exactly what had gone wrong with impersonation. Revoking a support
 * session deletes its Redis key, and the HTTP filter rejects the token from that
 * moment. This interceptor checked only the JWT signature, so an ended session
 * kept a live editor connection: ops were still applied to the customer's
 * template and rebroadcast to their other collaborators, for the remainder of
 * the token's lifetime. "End" was only ever true of HTTP.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String SID_ATTR = "impersonationSid";
    private static final String LIVE_UNTIL_ATTR = "impersonationLiveUntilNanos";
    private static final long LIVENESS_MEMO_MS = 2_000;

    private final JwtService jwtService;
    private final ImpersonationSessionService impersonationSessions;

    public WebSocketAuthInterceptor(JwtService jwtService,
                                     ImpersonationSessionService impersonationSessions) {
        this.jwtService = jwtService;
        this.impersonationSessions = impersonationSessions;
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
                        String impersonatedBy = claims.get("impersonatedBy", String.class);
                        String sid = claims.get("impersonationSid", String.class);

                        // Same rule as JwtAuthenticationFilter: for an
                        // impersonation token the signature is not enough, the
                        // session has to still be live. Fails closed.
                        if (impersonatedBy != null && !impersonationSessions.isLive(sid)) {
                            throw new MessageDeliveryException(message, "Impersonation session has ended");
                        }

                        String userId = claims.getSubject();
                        String email = claims.get("email", String.class);
                        String name = claims.get("name", String.class);

                        // Carried on every later frame of this STOMP session, so
                        // an already-open connection can be re-checked below.
                        Map<String, Object> attrs = accessor.getSessionAttributes();
                        if (sid != null && attrs != null) {
                            attrs.put(SID_ATTR, sid);
                        }

                        WebSocketPrincipal principal = new WebSocketPrincipal(
                                UUID.fromString(userId), name, email
                        );
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
                        accessor.setUser(authentication);
                    }
                }
            }
        } else if (!StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            // Re-check on every subsequent frame. Without this, revoking would
            // only stop *new* connections — the open editor would keep writing.
            //
            // DISCONNECT is deliberately exempt. Spring synthesises one when the
            // socket closes and pushes it through this same channel; blocking it
            // means SimpleBrokerMessageHandler.handleDisconnect never runs, which
            // is the only thing that drops the session and its subscriptions from
            // the broker registry. Refusing it leaked a phantom subscriber per
            // ended session, kept alive until restart.
            Map<String, Object> attrs = accessor.getSessionAttributes();
            Object sid = attrs == null ? null : attrs.get(SID_ATTR);
            if (sid instanceof String s && !isLiveCached(attrs, s)) {
                throw new MessageDeliveryException(message, "Impersonation session has ended");
            }
        }

        return message;
    }

    /**
     * Liveness with a very short positive memo.
     *
     * <p>Dragging an element emits an op per pointer move — on the order of 100
     * frames a second — and preSend runs on the socket's I/O thread. Without
     * this, each one is a blocking Redis round trip on a thread shared across
     * connections, which is fine against a local Redis and decidedly not fine
     * across a TLS hop.
     *
     * <p>Only <em>positive</em> results are memoised, and only for
     * {@link #LIVENESS_MEMO_MS}. A revoke is therefore honoured within that
     * window rather than instantly — the alternative is a per-frame network call
     * to shave a fraction of a second off a support session that is already
     * being torn down.
     */
    private boolean isLiveCached(Map<String, Object> attrs, String sid) {
        Object until = attrs.get(LIVE_UNTIL_ATTR);
        long now = System.nanoTime();
        if (until instanceof Long u && now < u) {
            return true;
        }
        if (!impersonationSessions.isLive(sid)) {
            attrs.remove(LIVE_UNTIL_ATTR);
            return false;
        }
        attrs.put(LIVE_UNTIL_ATTR, now + LIVENESS_MEMO_MS * 1_000_000L);
        return true;
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
