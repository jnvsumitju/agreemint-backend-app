package com.agreemint.service;

import org.springframework.security.core.context.SecurityContextHolder;
import com.agreemint.security.UserPrincipal;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ActivityService.class);

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
        stampImpersonation(entry);
        return activityLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<ActivityLogResponse> listRecent(UUID orgId, int limit) {
        return activityLogRepository
                // Over-fetch a little, because internal rows are dropped below
                // and the caller asked for `limit` rows they can actually see.
                .findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, limit * 2))
                .stream()
                .filter(ActivityService::isCustomerVisible)
                .limit(limit)
                .map(this::toResponse)
                .toList();
    }

    /**
     * Whether a row belongs in the customer's own feed.
     *
     * <p>Staff-originated events — impersonation sessions and data exports — are
     * recorded in the customer's org so staff can review them alongside the
     * actions they produced. But the row's {@code userName} is the operator's
     * internal email address, and this feed is readable by every member of the
     * org. Support staff identities are not the customer's to see. Staff still
     * get these rows in full through {@code /api/admin/audit}.
     *
     * <p>This is deliberately not a substitute for telling a customer that
     * staff signed in as them or exported their data. Hiding a row is not
     * disclosure, and that decision is still open.
     */
    private static boolean isCustomerVisible(ActivityLog a) {
        String action = a.getAction();
        if (action == null) return true;
        return !action.startsWith("impersonate.") && !action.startsWith("export.");
    }

    @Transactional(readOnly = true)
    public List<ActivityLogResponse> listRecentForTemplate(UUID orgId, UUID templateId, int limit) {
        return activityLogRepository
                .findByOrgIdAndTemplateIdOrderByCreatedAtDesc(orgId, templateId, PageRequest.of(0, limit * 2))
                .stream()
                .filter(ActivityService::isCustomerVisible)
                .limit(limit)
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

    /**
     * Record the operator when this action is happening inside an impersonated
     * session.
     *
     * <p>Read from the security context rather than threaded through every
     * call site: attribution is a cross-cutting concern, and touching all
     * ~20 log() callers to pass a value they do not otherwise care about would
     * guarantee someone forgets one.
     *
     * <p>Before this, an impersonated session wrote rows indistinguishable
     * from the target user's own activity — the {@code impersonatedBy} claim
     * existed on the token but was never read, so the audit trail quietly
     * misattributed everything a staff member did.
     */
    private void stampImpersonation(ActivityLog entry) {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return;
            if (!principal.isImpersonated()) return;

            entry.setMetadata(String.format(
                    "{\"impersonatedBy\":\"%s\",\"impersonationSid\":\"%s\"}",
                    principal.impersonatedBy(), principal.impersonationSid()));
        } catch (RuntimeException e) {
            // Never let attribution bookkeeping break the action being logged.
            log.warn("Could not stamp impersonation metadata: {}", e.getMessage());
        }
    }

}
