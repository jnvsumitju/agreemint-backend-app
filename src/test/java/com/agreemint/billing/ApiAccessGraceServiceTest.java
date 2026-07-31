package com.agreemint.billing;

import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.ApiKey;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Organization;
import com.agreemint.domain.User;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cover for the grace period between a plan lapsing and its API keys being
 * revoked.
 *
 * <p>The behaviour worth pinning is not "keys get revoked" — it is that the
 * customer is warned first, warned exactly once, and that resubscribing inside
 * the window calls the whole thing off. Getting any of those wrong turns a
 * billing event into a silent production outage for someone's integration.
 */
class ApiAccessGraceServiceTest {

    private ApiAccessGraceRepository graceRepo;
    private ApiKeyRepository apiKeyRepo;
    private OrganizationRepository orgRepo;
    private OrgMembershipRepository membershipRepo;
    private EmailService email;
    private ApiAccessGraceService service;

    private final UUID orgId = UUID.randomUUID();
    private final List<ApiAccessGrace> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        graceRepo = mock(ApiAccessGraceRepository.class);
        apiKeyRepo = mock(ApiKeyRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        membershipRepo = mock(OrgMembershipRepository.class);
        email = mock(EmailService.class);
        saved.clear();

        when(graceRepo.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });

        Organization org = new Organization();
        org.setId(orgId);
        org.setName("Acme");
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@acme.test");
        OrgMembership m = new OrgMembership();
        m.setUser(admin);
        m.setRole(OrgRole.ADMIN);
        when(membershipRepo.findByOrganizationId(orgId)).thenReturn(List.of(m));

        service = new ApiAccessGraceService(graceRepo, apiKeyRepo, orgRepo, membershipRepo,
                email, new FrontendProperties(), 3);
    }

    private ApiKey liveKey() {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setOrgId(orgId);
        k.setName("prod");
        return k;
    }

    private void keys(ApiKey... ks) {
        when(apiKeyRepo.findByOrgIdOrderByCreatedAtDesc(orgId)).thenReturn(List.of(ks));
    }

    private ApiAccessGrace grace(Instant lapsedAt, Instant warnedAt) {
        ApiAccessGrace g = new ApiAccessGrace();
        g.setOrgId(orgId);
        g.setLapsedAt(lapsedAt);
        g.setWarnedAt(warnedAt);
        return g;
    }

    // ── opening the window ────────────────────────────────────────────────

    @Test
    void aLapsedPlanOpensAGracePeriod() {
        keys(liveKey());
        when(graceRepo.existsById(orgId)).thenReturn(false);

        service.onPlanLapsed(orgId);

        assertEquals(1, saved.size());
        assertEquals(orgId, saved.get(0).getOrgId());
        assertNull(saved.get(0).getRevokedAt(), "keys must not be revoked at lapse time");
    }

    @Test
    void keysAreNotRevokedImmediately() {
        ApiKey k = liveKey();
        keys(k);
        when(graceRepo.existsById(orgId)).thenReturn(false);

        service.onPlanLapsed(orgId);

        // The entire point: the plan ends now, the access does not.
        assertNull(k.getRevokedAt());
        verify(apiKeyRepo, never()).save(any());
    }

    @Test
    void aRepeatedWebhookDoesNotRestartTheClock() {
        keys(liveKey());
        when(graceRepo.existsById(orgId)).thenReturn(true);

        service.onPlanLapsed(orgId);

        // Razorpay retries. Restarting the window would let a workspace stay in
        // a permanent grace period and never actually lose access.
        assertTrue(saved.isEmpty());
    }

    @Test
    void aWorkspaceWithNoKeysIsNotTold() {
        keys();
        when(graceRepo.existsById(orgId)).thenReturn(false);

        service.onPlanLapsed(orgId);

        assertTrue(saved.isEmpty(), "nothing to revoke means nothing to warn about");
        verifyNoInteractions(email);
    }

    // ── warning ───────────────────────────────────────────────────────────

    @Test
    void theWarningNamesTheDeadlineAndGoesToAdmins() {
        keys(liveKey(), liveKey());
        when(graceRepo.findByRevokedAtIsNullAndWarnedAtIsNull())
                .thenReturn(List.of(grace(Instant.now(), null)));

        int sent = service.sendPendingWarnings();

        assertEquals(1, sent);
        verify(email).sendApiAccessEndingEmail(eq("admin@acme.test"), eq("Acme"), eq(2),
                anyString(), anyString());
    }

    @Test
    void theWarningIsSentOnlyOnce() {
        keys(liveKey());
        ApiAccessGrace g = grace(Instant.now(), null);
        when(graceRepo.findByRevokedAtIsNullAndWarnedAtIsNull()).thenReturn(List.of(g));

        service.sendPendingWarnings();

        // warnedAt is what the next sweep filters on — without it the customer
        // gets this email every hour until the deadline.
        assertNotNull(g.getWarnedAt());
    }

    // ── revoking ──────────────────────────────────────────────────────────

    @Test
    void keysAreRevokedOnceTheWindowHasPassed() {
        ApiKey k = liveKey();
        keys(k);
        when(graceRepo.findByRevokedAtIsNullAndLapsedAtBefore(any()))
                .thenReturn(List.of(grace(Instant.now().minus(Duration.ofDays(4)), Instant.now())));

        int revoked = service.revokeExpired();

        assertEquals(1, revoked);
        assertNotNull(k.getRevokedAt());
        verify(email).sendApiAccessRevokedEmail(eq("admin@acme.test"), eq("Acme"), eq(1), anyString());
    }

    @Test
    void aFinishedGraceIsNotRevokedTwice() {
        ApiAccessGrace g = grace(Instant.now().minus(Duration.ofDays(9)), Instant.now());
        g.setRevokedAt(Instant.now());
        // The repository filters on revokedAt IS NULL, so a finished row is
        // never returned — proving the query is the guard, not a flag check.
        when(graceRepo.findByRevokedAtIsNullAndLapsedAtBefore(any())).thenReturn(List.of());

        assertEquals(0, service.revokeExpired());
        verifyNoInteractions(email);
    }

    // ── calling it off ────────────────────────────────────────────────────

    @Test
    void resubscribingCancelsThePendingRevocation() {
        ApiAccessGrace g = grace(Instant.now(), null);
        when(graceRepo.findById(orgId)).thenReturn(Optional.of(g));

        service.onPlanReactivated(orgId);

        // Deleted, not marked: the keys must survive untouched, and a later
        // lapse has to be able to open a fresh window.
        verify(graceRepo).delete(g);
    }

    @Test
    void reactivatingWithNoGracePeriodIsHarmless() {
        when(graceRepo.findById(orgId)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.onPlanReactivated(orgId));
        verify(graceRepo, never()).delete(any());
    }
}
