package com.agreemint.api.dto;

/**
 * Public-safe summary of an {@code OrgInvitation} returned when the registration
 * page loads with a {@code ?invite=<token>} query parameter. Lets the UI
 * pre-fill + lock the email field so the invitee can't accidentally register
 * with a different address than the one the admin invited.
 *
 * <p>Intentionally narrow — no user ids, no full org slug. {@code expired}
 * lets the UI surface "this invite has expired" without exposing other state.
 */
public record InvitationResolveResponse(
        String email,
        String orgName,
        String inviterName,
        String role,
        boolean expired
) {}
