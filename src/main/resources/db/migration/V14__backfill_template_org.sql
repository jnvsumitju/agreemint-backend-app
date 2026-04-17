-- V14: Backfill org_id / owner_id on templates that were created before
-- TemplateService.create() started stamping them (the bug where any VIEWER
-- got ADMIN-level access because OrgAuthorizationService treated null-org
-- templates as "legacy: allow anyone").
--
-- Strategy: for each template with org_id = NULL, pick the creator user's
-- first org membership (ordered by created_at) and stamp both org_id and
-- owner_id. If the creator has no membership the template is left orphaned
-- and the new authz layer will reject access until an admin fixes it.
--
-- Template.created_by is a free-form varchar (email / display name). Match
-- it against users.email first, then users.name as a fallback.

UPDATE templates t
SET
    org_id   = om.org_id,
    owner_id = om.user_id
FROM (
    SELECT DISTINCT ON (om_inner.user_id)
        om_inner.user_id, om_inner.org_id
    FROM org_memberships om_inner
    ORDER BY om_inner.user_id, om_inner.created_at ASC
) om
JOIN users u ON u.id = om.user_id
WHERE t.org_id IS NULL
  AND (
      u.email = LOWER(TRIM(t.created_by))
   OR u.name  = t.created_by
  );
