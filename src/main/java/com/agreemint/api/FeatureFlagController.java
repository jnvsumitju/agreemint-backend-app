package com.agreemint.api;

import com.agreemint.security.UserPrincipal;
import com.agreemint.service.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Feature flags resolved for the caller's organisation.
 *
 * <p>The counterpart to the staff-only flag admin: this is what the product
 * actually reads. Before it existed, flags could be toggled but never observed.
 */
@Tag(name = "Feature flags")
@RestController
@RequestMapping("/api/flags")
public class FeatureFlagController {

    private final FeatureFlagService flags;

    public FeatureFlagController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @Operation(summary = "Flags for the current organisation",
            description = "Every known flag with its resolved value: a per-org override "
                    + "if present, otherwise the flag's default. Unknown keys are absent "
                    + "and should be treated as off.")
    @GetMapping
    public Map<String, Boolean> current(@AuthenticationPrincipal UserPrincipal principal) {
        return flags.resolveAll(principal == null ? null : principal.orgId());
    }
}
