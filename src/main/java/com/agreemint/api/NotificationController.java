package com.agreemint.api;

import com.agreemint.api.dto.NotificationResponse;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Notifications", description = "User notifications and read status")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return notificationService.listRecent(principal.userId(), limit);
    }

    public record UnreadCount(long count) {}

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return new UnreadCount(notificationService.countUnread(principal.userId()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        notificationService.markRead(principal.userId(), id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.userId());
        return ResponseEntity.ok().build();
    }
}
