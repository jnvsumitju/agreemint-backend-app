package com.agreemint.api.dto;

/**
 * Resolved limits for a workspace, so the console can show a limit before the
 * user runs into a 402.
 *
 * <p>{@code freeRestricted} is the important one: it is false for paid plans
 * <em>and</em> for free workspaces created before the restrictions cutover, so
 * the UI never shows a grandfathered customer a cap that does not apply to them.
 *
 * @param maxTemplates   0 when unlimited
 * @param maxWorkspaces  0 when unlimited
 */
public record OrgEntitlementsResponse(
        String plan,
        boolean freeRestricted,
        int maxTemplates,
        long templateCount,
        int maxWorkspaces
) {}
