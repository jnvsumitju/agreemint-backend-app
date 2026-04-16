package com.agreemint.collab;

import com.agreemint.config.WebSocketAuthInterceptor.WebSocketPrincipal;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP handlers for structural collaborative ops and snapshot requests.
 *
 * <p>Client → server:
 * <ul>
 *     <li>{@code /app/template/{id}/op} — submit a {@link CollabOp} (with {@code clientOpId})</li>
 *     <li>{@code /app/template/{id}/snapshot} — request the current hot layout</li>
 * </ul>
 *
 * <p>Server → client:
 * <ul>
 *     <li>{@code /topic/template/{id}/ops} — rebroadcast of every accepted op</li>
 *     <li>{@code /user/queue/template/{id}/snapshot} — reply to the snapshot request</li>
 * </ul>
 */
@Controller
public class CollabController {

    private static final Logger log = LoggerFactory.getLogger(CollabController.class);

    private final CollabService collabService;
    private final OrgAuthorizationService orgAuthz;
    private final SimpMessagingTemplate messaging;

    public CollabController(
            CollabService collabService,
            OrgAuthorizationService orgAuthz,
            SimpMessagingTemplate messaging) {
        this.collabService = collabService;
        this.orgAuthz = orgAuthz;
        this.messaging = messaging;
    }

    /**
     * Client sends {@code { clientOpId, op: {...} }}. Server authenticates + authorises
     * (ADMIN / DESIGNER), applies to Redis, and rebroadcasts via CollabService.
     */
    @MessageMapping("/template/{templateId}/op")
    public void op(@DestinationVariable UUID templateId, OpEnvelope envelope, Principal principal) {
        if (envelope == null || envelope.op() == null) return;

        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp == null) {
            log.debug("Dropping op on template {} from unauthenticated session", templateId);
            return;
        }

        try {
            orgAuthz.assertTemplateAccess(wsp.getUserId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        } catch (ResponseStatusException ex) {
            log.debug("Dropping op on template {} from user {}: {}", templateId, wsp.getUserId(), ex.getReason());
            return;
        }

        try {
            collabService.applyOp(templateId, envelope.op(), wsp.getUserId(), envelope.clientOpId());
        } catch (RuntimeException ex) {
            log.warn("Failed to apply op on template {}: {}", templateId, ex.getMessage());
        }
    }

    /**
     * Client requests the current hot layout + seq. Server replies on the user-specific
     * queue {@code /user/queue/template/{id}/snapshot}.
     */
    @MessageMapping("/template/{templateId}/snapshot")
    public void snapshot(@DestinationVariable UUID templateId, Principal principal) {
        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp == null) return;

        // Subscribe access: viewers/reviewers can observe too.
        try {
            orgAuthz.assertTemplateAccess(wsp.getUserId(), templateId,
                    OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        } catch (ResponseStatusException ex) {
            return;
        }

        CollabService.Snapshot snap = collabService.snapshot(templateId);
        Map<String, Object> payload = Map.of(
                "layout", snap.layout(),
                "seq", snap.seq()
        );
        // Per-user snapshot topic — each client subscribes to its own userId-scoped path.
        // This avoids configuring a user destination prefix on the simple broker.
        messaging.convertAndSend(
                "/topic/template/" + templateId + "/snapshot/" + wsp.getUserId(),
                payload
        );
    }

    private WebSocketPrincipal extractPrincipal(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            Object inner = auth.getPrincipal();
            if (inner instanceof WebSocketPrincipal wsp) return wsp;
        }
        return null;
    }

    public record OpEnvelope(String clientOpId, CollabOp op) {}
}
