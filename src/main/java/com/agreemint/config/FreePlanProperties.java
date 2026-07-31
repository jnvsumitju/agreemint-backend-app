package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Limits applied to the FREE plan, and the cutover that grandfathers existing
 * workspaces out of them.
 *
 * <p><strong>{@link #restrictionsFrom} is null by default, which disables every
 * restriction below.</strong> That is deliberate: these rules take away
 * capability that free workspaces have had since launch, and switching them on
 * silently at deploy time would break people mid-task. Set it explicitly — to
 * the moment you want the new terms to start applying — and every workspace
 * created before that instant keeps the old behaviour forever.
 *
 * <pre>
 *   AGREEMINT_FREE_RESTRICTIONS_FROM=2026-08-01T00:00:00Z
 * </pre>
 *
 * <p>Grandfathering by creation timestamp rather than a per-org flag keeps this
 * to one config value with no migration, and makes the rule auditable: "created
 * before X" is something you can check in SQL.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.free")
public class FreePlanProperties {

    /**
     * Workspaces created at or after this instant are subject to the free-plan
     * limits. Null (the default) means no workspace is restricted.
     */
    private Instant restrictionsFrom;

    /** Maximum templates a restricted free workspace may own. */
    private int maxTemplates = 10;

    /** Maximum workspaces a user on free may create. */
    private int maxWorkspaces = 1;

    /**
     * Whether a workspace created at {@code orgCreatedAt} falls under the new
     * free-plan terms. Callers must also check that the org is actually on FREE.
     */
    public boolean appliesTo(Instant orgCreatedAt) {
        if (restrictionsFrom == null) return false;
        if (orgCreatedAt == null) return false;
        return !orgCreatedAt.isBefore(restrictionsFrom);
    }

    public boolean isEnabled() {
        return restrictionsFrom != null;
    }

    public Instant getRestrictionsFrom() { return restrictionsFrom; }
    public void setRestrictionsFrom(Instant v) { this.restrictionsFrom = v; }
    public int getMaxTemplates() { return maxTemplates; }
    public void setMaxTemplates(int v) { this.maxTemplates = v; }
    public int getMaxWorkspaces() { return maxWorkspaces; }
    public void setMaxWorkspaces(int v) { this.maxWorkspaces = v; }
}
