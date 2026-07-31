package com.agreemint.config;

import com.agreemint.admin.service.ImpersonationSessionService;
import com.agreemint.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Cover for the rule that revocation applies to the collab WebSocket too.
 *
 * <p>{@code SecurityConfig} marks {@code /ws/**} permitAll, so
 * {@code JwtAuthenticationFilter} — where the impersonation session check used
 * to live exclusively — never runs for this transport. The result was that
 * ending a support session stopped HTTP but left an open editor connection
 * writing into the customer's template for the rest of the token's lifetime.
 * These tests pin the check on both CONNECT and later frames.
 */
class WebSocketImpersonationAuthTest {

    private JwtService jwt;
    private ImpersonationSessionService sessions;
    private WebSocketAuthInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private static final String SID = "sid-123";

    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        sessions = mock(ImpersonationSessionService.class);
        interceptor = new WebSocketAuthInterceptor(jwt, sessions);
    }

    /** A token whose claims say what we want; signature validity is JwtService's job. */
    private void tokenClaims(String impersonatedBy, String sid) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("email", String.class)).thenReturn("target@example.com");
        when(claims.get("name", String.class)).thenReturn("Target");
        when(claims.get("impersonatedBy", String.class)).thenReturn(impersonatedBy);
        when(claims.get("impersonationSid", String.class)).thenReturn(sid);
        when(jwt.extractClaimsOrNull(anyString())).thenReturn(claims);
    }

    private Message<byte[]> connect(Map<String, Object> sessionAttrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer any-token");
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> send(Map<String, Object> sessionAttrs) {
        return frame(StompCommand.SEND, sessionAttrs);
    }

    private Message<byte[]> frame(StompCommand command, Map<String, Object> sessionAttrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** Bypass the positive memo so each assertion sees a fresh liveness check. */
    private static void expireMemo(Map<String, Object> attrs) {
        attrs.remove("impersonationLiveUntilNanos");
    }

    @Test
    void anOrdinaryTokenConnectsWithoutConsultingTheRegistry() {
        tokenClaims(null, null);
        Map<String, Object> attrs = new HashMap<>();

        assertDoesNotThrow(() -> interceptor.preSend(connect(attrs), null));

        // Normal collab traffic must not pay for a Redis lookup per frame.
        verifyNoInteractions(sessions);
        assertFalse(attrs.containsKey("impersonationSid"));
    }

    @Test
    void aLiveImpersonationTokenConnects() {
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();

        assertDoesNotThrow(() -> interceptor.preSend(connect(attrs), null));

        // Stashed so later frames on this connection can be re-checked.
        assertEquals(SID, attrs.get("impersonationSid"));
    }

    @Test
    void aRevokedImpersonationTokenCannotConnect() {
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(false);

        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(connect(new HashMap<>()), null));
    }

    @Test
    void revokingTearsDownAnAlreadyOpenConnection() {
        // The connection was established while the session was live...
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        interceptor.preSend(connect(attrs), null);

        // ...then a staff member ends it. The next frame must not go through:
        // this is the difference between End meaning "no new connections" and
        // End meaning the operator stops editing.
        when(sessions.isLive(SID)).thenReturn(false);
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(send(attrs), null));
    }

    @Test
    void disconnectIsNeverBlocked() {
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        interceptor.preSend(connect(attrs), null);

        when(sessions.isLive(SID)).thenReturn(false);
        expireMemo(attrs);

        // Spring synthesises a DISCONNECT through this same channel when the
        // socket closes, and SimpleBrokerMessageHandler.handleDisconnect is the
        // only thing that drops the session and its subscriptions. Blocking it
        // leaked a phantom subscriber per ended session, until restart.
        assertDoesNotThrow(() -> interceptor.preSend(frame(StompCommand.DISCONNECT, attrs), null));
    }

    @Test
    void livenessIsMemoisedBrieflySoADragDoesNotHammerRedis() {
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        interceptor.preSend(connect(attrs), null);
        clearInvocations(sessions);

        // Dragging emits ~100 ops/sec and preSend runs on the socket I/O thread;
        // one Redis round trip per frame is not affordable over a TLS hop.
        for (int i = 0; i < 50; i++) {
            interceptor.preSend(send(attrs), null);
        }
        verify(sessions, atMost(1)).isLive(SID);
    }

    @Test
    void aNegativeResultIsNotMemoised() {
        tokenClaims(UUID.randomUUID().toString(), SID);
        when(sessions.isLive(SID)).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        interceptor.preSend(connect(attrs), null);

        when(sessions.isLive(SID)).thenReturn(false);
        expireMemo(attrs);
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(send(attrs), null));

        // Still rejected on the next frame — a dead session must not be able to
        // ride a stale positive memo back to life.
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(send(attrs), null));
    }

    @Test
    void framesOnAnOrdinarySessionAreNotChecked() {
        Map<String, Object> attrs = new HashMap<>();
        assertDoesNotThrow(() -> interceptor.preSend(send(attrs), null));
        verifyNoInteractions(sessions);
    }

    @Test
    void aFrameWithNoSessionAttributesIsHarmless() {
        assertDoesNotThrow(() -> interceptor.preSend(send(null), null));
        verifyNoInteractions(sessions);
    }
}
