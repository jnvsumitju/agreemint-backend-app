package com.agreemint.api;

import com.agreemint.api.dto.ActivityLogResponse;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ActivityService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Activity", description = "Activity log and audit trail")
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public List<ActivityLogResponse> listRecent(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "20") int limit,
            // When supplied, the feed is narrowed to events tied to this
            // template (template-direct rows + lifecycle/approval rows on
            // documents generated from this template). Org-wide listing
            // remains available when the param is omitted.
            @RequestParam(required = false) UUID templateId
    ) {
        if (templateId != null) {
            return activityService.listRecentForTemplate(principal.orgId(), templateId, limit);
        }
        return activityService.listRecent(principal.orgId(), limit);
    }
}
