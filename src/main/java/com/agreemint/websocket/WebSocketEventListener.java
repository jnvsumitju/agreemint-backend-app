package com.agreemint.websocket;

import com.agreemint.config.WebSocketAuthInterceptor.WebSocketPrincipal;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class WebSocketEventListener {

    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(PresenceService presenceService, SimpMessagingTemplate messagingTemplate) {
        this.presenceService = presenceService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }

        WebSocketPrincipal wsPrincipal = extractPrincipal(principal);
        if (wsPrincipal == null) {
            return;
        }

        List<UUID> affectedTemplates = presenceService.leaveAll(wsPrincipal.getUserId());
        for (UUID templateId : affectedTemplates) {
            Set<PresenceService.UserPresence> remaining = presenceService.getPresence(templateId);
            PresenceMessage message = toPresenceMessage(remaining);
            messagingTemplate.convertAndSend(
                    "/topic/template/" + templateId + "/presence",
                    message
            );
        }
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
