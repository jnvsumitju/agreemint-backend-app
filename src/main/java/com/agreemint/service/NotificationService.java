package com.agreemint.service;

import com.agreemint.api.dto.NotificationResponse;
import com.agreemint.domain.Notification;
import com.agreemint.domain.User;
import com.agreemint.repository.NotificationRepository;
import com.agreemint.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepo;
    private final UserRepository userRepo;

    public NotificationService(NotificationRepository notificationRepo, UserRepository userRepo) {
        this.notificationRepo = notificationRepo;
        this.userRepo = userRepo;
    }

    /** Create a notification for a specific user. */
    @Transactional
    public Notification notify(UUID userId, String type, String title, String body,
                               String entityType, UUID entityId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Notification n = new Notification();
        n.setUser(user);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        return notificationRepo.save(n);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listRecent(UUID userId, int limit) {
        return notificationRepo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepo.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = notificationRepo.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!n.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        n.setRead(true);
        notificationRepo.save(n);
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepo.markAllReadByUserId(userId);
    }
}
