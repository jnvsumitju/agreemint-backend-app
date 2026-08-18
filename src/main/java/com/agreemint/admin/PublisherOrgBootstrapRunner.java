package com.agreemint.admin;

import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Organization;
import com.agreemint.domain.User;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.Set;

/**
 * Ensures the first-party publisher workspace exists and that staff can edit in it.
 *
 * <p>The twenty free templates are published from a real Crixaa-owned org rather
 * than from some "system" pseudo-owner, and staff hold an ordinary
 * {@link OrgRole#DESIGNER} membership in it. That is the whole design: "a staff
 * account can edit these without forking" then falls out of the authorization
 * that already exists, and no bypass is added to {@code OrgAuthorizationService}.
 *
 * <p>The alternative — an {@code isStaff} check that skips org scoping for
 * first-party rows — would put a privileged write path through the same code
 * that guards every customer's templates. This codebase has already shipped one
 * cross-tenant marketplace hole; a second one there would be worse, because it
 * would be deliberate.
 *
 * <p>ADMIN, because the job includes inviting other people into this workspace
 * with roles of their own. DESIGNER covers editing and publishing templates but
 * cannot invite members, which would leave the publisher workspace permanently
 * staffed by whoever the bootstrap happened to promote. Configurable via
 * {@code agreemint.publisher.staff-role} for a deployment that wants it
 * tighter.
 *
 * <p>Runs after {@link StaffBootstrapRunner} ({@code @Order(0)}) so the accounts
 * it grants membership to have had their staff flag set in the same startup.
 * Idempotent, and it NEVER modifies a membership that already exists — not to
 * raise it, not to lower it. Once someone is in this workspace, their role is
 * whatever an admin deliberately gave them, and a restart is not an opinion
 * about that. The bootstrap's job ends at getting the first staff account in.
 *
 * <p>The consequence worth knowing: an account whose membership was created
 * under an earlier {@code staff-role} keeps that role forever. To change it,
 * either use the workspace's own member management, or delete the membership
 * row and restart — an absent membership is recreated at the configured role.
 *
 * <p>Disabled unless {@code agreemint.publisher.enabled} is true, so a
 * development database never grows an org nobody asked for.
 */
@Component
@Order(1)
public class PublisherOrgBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PublisherOrgBootstrapRunner.class);

    /** The environment variable operators actually set; the property is derived from it. */
    private static final String ENV_VAR = "AGREEMINT_PUBLISHER_ENABLED";

    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final UserRepository userRepo;
    private final OfficialTemplateSeeder seeder;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final boolean enabled;
    private final String slug;
    private final String name;
    private final OrgRole staffRole;

    public PublisherOrgBootstrapRunner(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            UserRepository userRepo,
            OfficialTemplateSeeder seeder,
            org.springframework.transaction.support.TransactionTemplate tx,
            @Value("${agreemint.publisher.enabled:false}") boolean enabled,
            @Value("${agreemint.publisher.slug:crixaa}") String slug,
            @Value("${agreemint.publisher.name:Crixaa}") String name,
            @Value("${agreemint.publisher.staff-role:ADMIN}") OrgRole staffRole) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.userRepo = userRepo;
        this.seeder = seeder;
        this.tx = tx;
        this.enabled = enabled;
        this.slug = slug;
        this.name = name;
        this.staffRole = staffRole;
    }

    @Override
    public void run(String... args) {
        // Always says something. Returning silently when disabled made "flag
        // off", "ran and changed nothing" and "never invoked" indistinguishable
        // in a deployed environment — the only three things worth telling apart.
        if (!enabled) {
            // Report the RAW environment variable next to the resolved property.
            // "false" on its own cannot distinguish "the variable is not in this
            // container" from "it is set but did not bind" — and the first is
            // the common one, because Docker fixes a container's environment at
            // creation, so adding -e and running `docker restart` changes
            // nothing. Printing both makes the next boot self-diagnosing.
            String raw = System.getenv(ENV_VAR);
            log.warn("[publisher-bootstrap] Disabled — agreemint.publisher.enabled resolved to false"
                    + " ({}={}). The '{}' workspace and the free templates will not be created."
                    + (raw == null
                        ? " That variable is not present in this process: if you added it with -e,"
                          + " the container must be recreated, not restarted."
                        : " The variable IS present, so this is a binding problem, not a missing"
                          + " value — check for an application.yml or profile overriding it."),
                    ENV_VAR, raw == null ? "<not set>" : raw, slug);
            return;
        }
        log.info("[publisher-bootstrap] Enabled — ensuring workspace '{}'.", slug);

        // Deliberately NOT @Transactional on this method. The seeder catches a
        // failing bundle and carries on, which inside one big transaction is a
        // trap: the first persistence error marks it rollback-only, the run
        // logs success for the other nineteen, and then the commit throws and
        // discards the workspace and memberships too. Separate transactions
        // mean a bad bundle costs only itself.
        Organization org = tx.execute(status -> {
            Organization o = ensureOrg();
            grantStaffMembership(o);
            return o;
        });
        if (org == null) {
            log.error("[publisher-bootstrap] Could not establish the publisher workspace.");
            return;
        }
        int touched = seeder.seed(org.getId(), name);
        log.info("[publisher-bootstrap] Done — workspace '{}' id={}, {} listing(s) touched.",
                slug, org.getId(), touched);
    }

    /**
     * The publisher org sits on {@link OrgPlan#ENTERPRISE}: publishing is
     * Starter+ and template counts are plan-capped, and staff hitting a quota
     * wall while maintaining the free catalogue would be a self-inflicted
     * outage. The plan is only ever raised, never lowered, so an operator who
     * deliberately changed it keeps their change.
     */
    private Organization ensureOrg() {
        Organization org = orgRepo.findBySlug(slug).orElse(null);
        if (org == null) {
            org = new Organization();
            org.setName(name);
            org.setSlug(slug);
            org.setPlan(OrgPlan.ENTERPRISE);
            org = orgRepo.save(org);
            log.info("[publisher-bootstrap] Created publisher workspace '{}' ({}).", name, slug);
            return org;
        }
        if (org.getPlan() != OrgPlan.ENTERPRISE) {
            log.info("[publisher-bootstrap] Raising publisher workspace '{}' from {} to ENTERPRISE.",
                    slug, org.getPlan());
            org.setPlan(OrgPlan.ENTERPRISE);
            org = orgRepo.save(org);
        }
        return org;
    }

    /**
     * The one case where an existing role is overridden: nobody can administer
     * the workspace.
     *
     * <p>A workspace with no ADMIN cannot invite anyone or change any role, so
     * it cannot be corrected from inside the product — the buttons that would
     * fix it are the ones that require the role nobody has. That is a dead end
     * rather than a preference, which is why it is worth a carve-out from
     * "invited roles always win".
     *
     * <p>Narrow on purpose. It promotes exactly ONE account, not every staff
     * member; it promotes to ADMIN specifically rather than to the configured
     * {@code staff-role}, since a configured VIEWER would leave the workspace
     * just as unadministrable; and it cannot fire again once any ADMIN exists,
     * so a deliberate demotion of one admin among several is left alone.
     */
    private void ensureSomebodyCanAdminister(Organization org, List<User> staff) {
        List<OrgMembership> members = membershipRepo.findByOrganizationId(org.getId());
        if (members.stream().anyMatch(m -> m.getRole() == OrgRole.ADMIN)) return;
        if (members.isEmpty()) return;

        Set<UUID> staffIds = staff.stream().map(User::getId).collect(java.util.stream.Collectors.toSet());
        // Deterministic by user id so repeated boots pick the same account
        // rather than promoting a different person each restart.
        OrgMembership promote = members.stream()
                .filter(m -> m.getUser() != null && staffIds.contains(m.getUser().getId()))
                .min(java.util.Comparator.comparing(m -> m.getUser().getId()))
                .orElse(null);
        if (promote == null) {
            log.warn("[publisher-bootstrap] Workspace '{}' has no ADMIN and no staff member to "
                    + "promote. Nobody can invite members or change roles in it.", slug);
            return;
        }
        log.warn("[publisher-bootstrap] Workspace '{}' had no ADMIN — promoting staff account {} "
                        + "from {} to ADMIN so the workspace can be administered. This is the only "
                        + "case where an existing role is overridden.",
                slug, promote.getUser().getEmail(), promote.getRole());
        promote.setRole(OrgRole.ADMIN);
        membershipRepo.save(promote);
    }

    private void grantStaffMembership(Organization org) {
        List<User> staff = userRepo.findByStaffTrue();
        if (staff.isEmpty()) {
            log.warn("[publisher-bootstrap] No staff accounts yet — set agreemint.staff-emails and "
                    + "restart, or nobody will be able to edit the first-party templates.");
            return;
        }
        int added = 0;
        int kept = 0;
        for (User u : staff) {
            var existing = membershipRepo.findByUserIdAndOrganizationId(u.getId(), org.getId());
            if (existing.isPresent()) {
                // Deliberately untouched. Whatever role this account holds was
                // set by someone with the authority to set it, and a restart
                // must not overrule that in either direction.
                kept++;
                continue;
            }
            OrgMembership m = new OrgMembership();
            m.setUser(u);
            m.setOrganization(org);
            m.setRole(staffRole);
            membershipRepo.save(m);
            added++;
        }
        log.info("[publisher-bootstrap] Publisher workspace '{}': {} newly added as {}, "
                        + "{} already members (roles left as set).",
                slug, added, staffRole, kept);

        ensureSomebodyCanAdminister(org, staff);
    }
}
