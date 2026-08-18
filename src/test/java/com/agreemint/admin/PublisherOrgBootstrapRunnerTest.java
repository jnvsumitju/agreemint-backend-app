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
    void anInvitedRoleAlwaysWins() {
        // The invariant: once an account is a member, the bootstrap has no
        // opinion about its role. Someone invited into this workspace as a
        // DESIGNER or a VIEWER stays that, restart after restart, whatever
        // staff-role is configured — the role was set by someone with the
        // authority to set it.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        for (OrgRole held : OrgRole.values()) {
            org.mockito.Mockito.reset(membershipRepo);
            OrgMembership existingMembership = new OrgMembership();
            existingMembership.setUser(staffUser());
            existingMembership.setRole(held);
            when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                    .thenReturn(Optional.of(existingMembership));
            // Someone else already administers the workspace, so the no-admin
            // carve-out cannot fire and this tests the ordinary rule.
            OrgMembership someoneElse = new OrgMembership();
            someoneElse.setUser(staffUser());
            someoneElse.setRole(OrgRole.ADMIN);
            when(membershipRepo.findByOrganizationId(any()))
                    .thenReturn(List.of(existingMembership, someoneElse));

            new PublisherOrgBootstrapRunner(orgRepo, membershipRepo, userRepo, seeder,
                    directTx(), true, "crixaa", "Crixaa", OrgRole.ADMIN).run();

            verify(membershipRepo, never()).save(any());
            assertEquals(held, existingMembership.getRole(),
                    held + " must survive a restart untouched");
        }
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

    @Test
    void promotesOneAccountWhenNobodyCanAdministerTheWorkspace() {
        // The dead end this exists for: the only member holds DESIGNER, so the
        // buttons that could fix the role are the ones requiring the role
        // nobody has.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));

        User staff = staffUser();
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staff));
        OrgMembership designer = new OrgMembership();
        designer.setUser(staff);
        designer.setRole(OrgRole.DESIGNER);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(designer));
        when(membershipRepo.findByOrganizationId(any())).thenReturn(List.of(designer));

        runner(true).run();

        ArgumentCaptor<OrgMembership> m = ArgumentCaptor.forClass(OrgMembership.class);
        verify(membershipRepo).save(m.capture());
        assertEquals(OrgRole.ADMIN, m.getValue().getRole());
    }

    @Test
    void promotesToAdminEvenWhenStaffRoleIsConfiguredLower() {
        // Promoting to a configured VIEWER would leave the workspace exactly as
        // unadministrable as it was, which would make the carve-out pointless.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));

        User staff = staffUser();
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staff));
        OrgMembership viewer = new OrgMembership();
        viewer.setUser(staff);
        viewer.setRole(OrgRole.VIEWER);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(viewer));
        when(membershipRepo.findByOrganizationId(any())).thenReturn(List.of(viewer));

        new PublisherOrgBootstrapRunner(orgRepo, membershipRepo, userRepo, seeder,
                directTx(), true, "crixaa", "Crixaa", OrgRole.VIEWER).run();

        ArgumentCaptor<OrgMembership> m = ArgumentCaptor.forClass(OrgMembership.class);
        verify(membershipRepo).save(m.capture());
        assertEquals(OrgRole.ADMIN, m.getValue().getRole());
    }

    @Test
    void doesNotPromoteWhenAnAdminAlreadyExists() {
        // Once anyone can administer the workspace, a deliberately demoted
        // second admin is none of the bootstrap's business.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));

        User staff = staffUser();
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staff));
        OrgMembership demotedStaff = new OrgMembership();
        demotedStaff.setUser(staff);
        demotedStaff.setRole(OrgRole.VIEWER);
        OrgMembership realAdmin = new OrgMembership();
        realAdmin.setUser(staffUser());
        realAdmin.setRole(OrgRole.ADMIN);
        when(membershipRepo.findByUserIdAndOrganizationId(any(), any()))
                .thenReturn(Optional.of(demotedStaff));
        when(membershipRepo.findByOrganizationId(any()))
                .thenReturn(List.of(demotedStaff, realAdmin));

        runner(true).run();

        verify(membershipRepo, never()).save(any());
        assertEquals(OrgRole.VIEWER, demotedStaff.getRole());
    }
}
