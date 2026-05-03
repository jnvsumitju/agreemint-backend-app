package com.agreemint.service;

import com.agreemint.api.dto.ActivityLogResponse;
import com.agreemint.domain.ActivityLog;
import com.agreemint.repository.ActivityLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityLogRepository activityLogRepository;

    public ActivityService(ActivityLogRepository activityLogRepository) {
        this.activityLogRepository = activityLogRepository;
    }

    @Transactional
    public ActivityLog log(UUID orgId, UUID userId, String userName, String action,
                           String entityType, UUID entityId, String entityName) {
        ActivityLog entry = new ActivityLog();
        entry.setOrgId(orgId);
        entry.setUserId(userId);
        entry.setUserName(userName);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setEntityName(entityName);
        return activityLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<ActivityLogResponse> listRecent(UUID orgId, int limit) {
        return activityLogRepository
                .findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, limit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityLogResponse> listRecentForTemplate(UUID orgId, UUID templateId, int limit) {
        return activityLogRepository
                .findByOrgIdAndTemplateIdOrderByCreatedAtDesc(orgId, templateId, PageRequest.of(0, limit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ActivityLogResponse toResponse(ActivityLog a) {
        return new ActivityLogResponse(
                a.getId(),
                a.getAction(),
                a.getEntityType(),
                a.getEntityId(),
                a.getEntityName(),
                a.getUserName(),
                a.getCreatedAt()
        );
    }
}
