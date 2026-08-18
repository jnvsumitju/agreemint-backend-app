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
                enabled, "crixaa", "Crixaa");
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
    void grantsStaffDesignerNotAdmin() {
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        when(membershipRepo.existsByUserIdAndOrganizationId(any(), any())).thenReturn(false);

        runner(true).run();

        ArgumentCaptor<OrgMembership> m = ArgumentCaptor.forClass(OrgMembership.class);
        verify(membershipRepo).save(m.capture());
        // DESIGNER can create, edit and publish templates — the entire job — and
        // cannot touch billing, invite members, or delete the workspace.
        assertEquals(OrgRole.DESIGNER, m.getValue().getRole());
    }

    @Test
    void existingMembershipIsLeftAlone() {
        // Re-running must not demote someone whose role was raised by hand;
        // silently changing access on a restart is a nasty surprise.
        Organization existing = new Organization();
        existing.setId(orgId);
        existing.setSlug("crixaa");
        existing.setPlan(OrgPlan.ENTERPRISE);
        when(orgRepo.findBySlug("crixaa")).thenReturn(Optional.of(existing));
        when(userRepo.findByStaffTrue()).thenReturn(List.of(staffUser()));
        when(membershipRepo.existsByUserIdAndOrganizationId(any(), any())).thenReturn(true);

        runner(true).run();

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
}
