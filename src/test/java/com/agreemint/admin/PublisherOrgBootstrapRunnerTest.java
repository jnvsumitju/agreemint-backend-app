package com.agreemint.admin;

import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Organization;
import com.agreemint.domain.User;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The publisher workspace is what makes "staff edit without forking" work
 * through ordinary org authorization instead of a bypass, so the properties
 * asserted here are the ones that keep it safe rather than merely working:
 * staff land as DESIGNER (not ADMIN), an existing membership is never
 * rewritten, and nothing at all happens unless it is explicitly enabled.
 */
class PublisherOrgBootstrapRunnerTest {

    private OrganizationRepository orgRepo;
    private OrgMembershipRepository membershipRepo;
    private UserRepository userRepo;
    private OfficialTemplateSeeder seeder;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        membershipRepo = mock(OrgMembershipRepository.class);
        userRepo = mock(UserRepository.class);
        seeder = mock(OfficialTemplateSeeder.class);
        when(orgRepo.save(any(Organization.class))).thenAnswer(i -> {
            Organization o = i.getArgument(0);
            if (o.getId() == null) o.setId(orgId);
            return o;
        });
    }

    private PublisherOrgBootstrapRunner runner(boolean enabled) {
        return new PublisherOrgBootstrapRunner(orgRepo, membershipRepo, userRepo, seeder,
                directTx(), enabled, "crixaa", "Crixaa", OrgRole.ADMIN);
    }

    private User staffUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("staff@crixaa.com");
        u.setStaff(true);
        return u;
    }

    @Test
    void disabledByDefaultTouchesNothing() {
        runner(false).run();
        verifyNoInteractions(orgRepo, membershipRepo, userRepo, seeder);
    }

    @Test
    void createsTheWorkspaceOnEnterpriseSoQuotasNeverBlockStaff() {
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.empty());
        when(userRepo.findByStaffTrue()).thenReturn(List.of());

        runner(true).run();

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(orgRepo).save(saved.capture());
        assertEquals("crixaa", saved.getValue().getSlug());
        // Publishing is Starter+ and template counts are plan-capped; a staff
        // member hitting a quota wall while maintaining the free catalogue would
        // be a self-inflicted outage.
        assertEquals(OrgPlan.ENTERPRISE, saved.getValue().getPlan());
    }

    @Test
    void grantsStaffAdminSoTheyCanInviteOthers() {
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any())).thenReturn(Optional.empty());

        runner(true).run();

        ArgumentCaptor<OrgMembership> m = ArgumentCaptor.forClass(OrgMembership.class);
        verify(membershipRepo).save(m.capture());
        // ADMIN, not DESIGNER: the job includes inviting other people into this
        // workspace, and DESIGNER cannot invite.
        assertEquals(OrgRole.ADMIN, m.getValue().getRole());
    }

    @Test
    void anExistingAdminIsLeftAlone() {
        // Steady state: nothing to change, so a restart writes nothing.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        OrgMembership existingMembership = new OrgMembership();
        existingMembership.setRole(OrgRole.ADMIN);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(existingMembership));

        runner(true).run();

        verify(membershipRepo, never()).save(any());
    }

    @Test
    void aStaffMemberBootstrappedUnderTheOldDesignerDefaultIsRaised() {
        // The role was DESIGNER first. Without raising, an account created under
        // that default would be permanently unable to invite anyone — which is
        // the whole reason for the change.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        OrgMembership designer = new OrgMembership();
        designer.setRole(OrgRole.DESIGNER);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(designer));

        runner(true).run();

        ArgumentCaptor<OrgMembership> m = ArgumentCaptor.forClass(OrgMembership.class);
        verify(membershipRepo).save(m.capture());
        assertEquals(OrgRole.ADMIN, m.getValue().getRole());
    }

    @Test
    void neverDemotesEvenIfConfiguredLower() {
        // Enum order is ADMIN, DESIGNER, REVIEWER, VIEWER — a LOWER ordinal is a
        // HIGHER privilege. Comparing the wrong way round would demote every
        // staff account on restart, silently, which is far worse than the gap it
        // would be trying to close.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        OrgMembership admin = new OrgMembership();
        admin.setRole(OrgRole.ADMIN);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(admin));

        new PublisherOrgBootstrapRunner(orgRepo, membershipRepo, userRepo, seeder,
                directTx(), true, "crixaa", "Crixaa", OrgRole.VIEWER).run();

        verify(membershipRepo, never()).save(any());
    }

    @Test
    void adoptsAnExistingWorkspaceRatherThanCreatingASecond() {
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of());

        runner(true).run();

        // Already correct: no write at all, so restarts stay quiet.
        verify(orgRepo, never()).save(any());
    }

    @Test
    void raisesAnUnderpoweredExistingWorkspace() {
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.FREE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of());

        runner(true).run();

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(orgRepo).save(saved.capture());
        assertEquals(OrgPlan.ENTERPRISE, saved.getValue().getPlan());
    }

    /**
     * A TransactionTemplate that just runs the callback.
     *
     * <p>Production isolates each unit of work so one failure cannot discard
     * the rest; the tests care about that control flow, not about real
     * transactions, so this keeps the call structure identical without a
     * database.
     */
    private static org.springframework.transaction.support.TransactionTemplate directTx() {
        var t = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { return new Object(); }
                    @Override protected void doBegin(Object o,
                            org.springframework.transaction.TransactionDefinition d) { }
                    @Override protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus s) { }
                    @Override protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus s) { }
                });
        return t;
    }
}
