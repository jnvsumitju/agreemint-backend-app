package com.agreemint.websocket;

import com.agreemint.config.WebSocketAuthInterceptor.WebSocketPrincipal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
public class PresenceController {

    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    public PresenceController(PresenceService presenceService, SimpMessagingTemplate messagingTemplate) {
        this.presenceService = presenceService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/template/{templateId}/join")
    public void join(@DestinationVariable UUID templateId, Principal principal) {
        WebSocketPrincipal wsPrincipal = extractPrincipal(principal);
        if (wsPrincipal == null) {
            return;
        }

        Set<PresenceService.UserPresence> users = presenceService.join(
                templateId,
                wsPrincipal.getUserId(),
                wsPrincipal.getDisplayName(),
                wsPrincipal.getEmail()
        );

        messagingTemplate.convertAndSend(
                "/topic/template/" + templateId + "/presence",
                toPresenceMessage(users)
        );
    }

    @MessageMapping("/template/{templateId}/leave")
    public void leave(@DestinationVariable UUID templateId, Principal principal) {
        WebSocketPrincipal wsPrincipal = extractPrincipal(principal);
        if (wsPrincipal == null) {
            return;
        }

        Set<PresenceService.UserPresence> users = presenceService.leave(
                templateId, wsPrincipal.getUserId()
        );

        messagingTemplate.convertAndSend(
                "/topic/template/" + templateId + "/presence",
                toPresenceMessage(users)
        );
    }

    @MessageMapping("/template/{templateId}/viewport")
    public void viewport(@DestinationVariable UUID templateId, Map<String, Object> payload, Principal principal) {
        // Overwrite userId to prevent spoofing — use the authenticated principal
        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp != null) {
            payload.put("userId", wsp.getUserId().toString());
        }
        messagingTemplate.convertAndSend(
                "/topic/template/" + templateId + "/viewport",
                payload
        );
    }

    /**
     * Selection broadcast: a client publishes the element ids it currently has
     * selected; the server rebroadcasts to everyone else so they can render
     * colored outlines indicating who is working on what.
     */
    @MessageMapping("/template/{templateId}/selection")
    public void selection(@DestinationVariable UUID templateId, Map<String, Object> payload, Principal principal) {
        WebSocketPrincipal wsp = extractPrincipal(principal);
        if (wsp != null) {
            payload.put("userId", wsp.getUserId().toString());
        }
        messagingTemplate.convertAndSend(
                "/topic/template/" + templateId + "/selection",
                payload
        );
    }

    private WebSocketPrincipal extractPrincipal(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            Object inner = auth.getPrincipal();
            if (inner instanceof WebSocketPrincipal wsp) {
                return wsp;
            }
        }
        return null;
    }

    private PresenceMessage toPresenceMessage(Set<PresenceService.UserPresence> users) {
        java.util.List<PresenceMessage.UserPresenceDto> dtos = users.stream()
                .map(p -> new PresenceMessage.UserPresenceDto(
                        p.userId().toString(),
                        p.name(),
                        p.email(),
                        p.color(),
                        p.connectedAt().toString()
                ))
                .toList();
        return new PresenceMessage(dtos);
    }
}
