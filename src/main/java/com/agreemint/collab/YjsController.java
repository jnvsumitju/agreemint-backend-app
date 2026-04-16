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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP handlers for Yjs binary collaboration.
 *
 * <p>Client → server:
 * <ul>
 *     <li>{@code /app/template/{id}/yjs} — push a Y.Doc update (base64 Uint8Array) + optional awareness</li>
 *     <li>{@code /app/template/{id}/yjs-snapshot} — push a compacted full-doc snapshot</li>
 *     <li>{@code /app/template/{id}/yjs-state} — request current state (on join)</li>
 * </ul>
 *
 * <p>Server → client:
 * <ul>
 *     <li>{@code /topic/template/{id}/yjs} — relayed update (fan-out, senders included for idempotency)</li>
 *     <li>{@code /topic/template/{id}/yjs-state/{userId}} — reply to a state request, per-user topic</li>
 * </ul>
 */
@Controller
public class YjsController {

    private static final Logger log = LoggerFactory.getLogger(YjsController.class);

    private final YjsService yjsService;
    private final OrgAuthorizationService orgAuthz;
    private final SimpMessagingTemplate messaging;

    public YjsController(
            YjsService yjsService,
            OrgAuthorizationService orgAuthz,
            SimpMessagingTemplate messaging) {
        this.yjsService = yjsService;
        this.orgAuthz = orgAuthz;
        this.messaging = messaging;
    }

    /**
     * Relay a Yjs update. Writers must have edit role; viewers observing the topic
     * still receive the broadcast but cannot send.
     */
    @MessageMapping("/template/{templateId}/yjs")
    public void update(
            @DestinationVariable UUID templateId,
            YjsUpdateEnvelope envelope,
            Principal principal) {
        if (envelope == null || envelope.update() == null || envelope.update().isEmpty()) return;

        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp == null) return;

        try {
            orgAuthz.assertTemplateAccess(wsp.getUserId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        } catch (ResponseStatusException ex) {
            log.debug("Dropping Yjs update on template {} from user {}: {}", templateId, wsp.getUserId(), ex.getReason());
            return;
        }

        try {
            yjsService.appendUpdate(templateId, envelope.update());
            messaging.convertAndSend("/topic/template/" + templateId + "/yjs",
                    Map.of(
                            "update", envelope.update(),
                            "awareness", envelope.awareness() == null ? "" : envelope.awareness(),
                            "userId", wsp.getUserId().toString()
                    ));
        } catch (RuntimeException ex) {
            log.warn("Yjs relay failed for template {}: {}", templateId, ex.getMessage());
        }
    }

    /**
     * Compact: replace the persisted state with a client-provided full snapshot.
     * Only edit roles may compact.
     */
    @MessageMapping("/template/{templateId}/yjs-snapshot")
    public void snapshot(
            @DestinationVariable UUID templateId,
            YjsSnapshotEnvelope envelope,
            Principal principal) {
        if (envelope == null || envelope.state() == null) return;

        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp == null) return;

        try {
            orgAuthz.assertTemplateAccess(wsp.getUserId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        } catch (ResponseStatusException ex) {
            return;
        }

        yjsService.replaceSnapshot(templateId, envelope.state());
    }

    /**
     * State request: server replies with the latest snapshot + pending updates on a
     * per-user topic. Read-only roles may also request state so they can observe.
     */
    @MessageMapping("/template/{templateId}/yjs-state")
    public void requestState(@DestinationVariable UUID templateId, Principal principal) {
        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp == null) return;

        try {
            orgAuthz.assertTemplateAccess(wsp.getUserId(), templateId,
                    OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        } catch (ResponseStatusException ex) {
            return;
        }

        YjsService.State s = yjsService.hydrate(templateId);
        Map<String, Object> payload = Map.of(
                "state", s.state() == null ? "" : s.state(),
                "updates", s.updates() == null ? List.of() : s.updates()
        );
        messaging.convertAndSend(
                "/topic/template/" + templateId + "/yjs-state/" + wsp.getUserId(),
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

    public record YjsUpdateEnvelope(String update, String awareness) {}
    public record YjsSnapshotEnvelope(String state) {}
}
