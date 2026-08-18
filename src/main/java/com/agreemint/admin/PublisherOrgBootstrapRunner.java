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
 * <p>DESIGNER, not ADMIN, on purpose: it can create, edit and publish templates,
 * which is the entire job, and cannot change billing, invite members, or remove
 * the workspace. Staff who need those already have the admin portal.
 *
 * <p>Runs after {@link StaffBootstrapRunner} ({@code @Order(0)}) so the accounts
 * it grants membership to have had their staff flag set in the same startup.
 * Idempotent: re-running adopts the existing org, and only adds the memberships
 * that are missing. An existing membership is left exactly as it is — including
 * a hand-raised ADMIN — because silently demoting someone's access on restart
 * would be a nasty surprise.
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

    public PublisherOrgBootstrapRunner(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            UserRepository userRepo,
            OfficialTemplateSeeder seeder,
            org.springframework.transaction.support.TransactionTemplate tx,
            @Value("${agreemint.publisher.enabled:false}") boolean enabled,
            @Value("${agreemint.publisher.slug:crixaa}") String slug,
            @Value("${agreemint.publisher.name:Crixaa}") String name) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.userRepo = userRepo;
        this.seeder = seeder;
        this.tx = tx;
        this.enabled = enabled;
        this.slug = slug;
        this.name = name;
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

    private void grantStaffMembership(Organization org) {
        List<User> staff = userRepo.findByStaffTrue();
        if (staff.isEmpty()) {
            log.warn("[publisher-bootstrap] No staff accounts yet — set agreemint.staff-emails and "
                    + "restart, or nobody will be able to edit the first-party templates.");
            return;
        }
        int added = 0;
        for (User u : staff) {
            if (membershipRepo.existsByUserIdAndOrganizationId(u.getId(), org.getId())) continue;
            OrgMembership m = new OrgMembership();
            m.setUser(u);
            m.setOrganization(org);
            m.setRole(OrgRole.DESIGNER);
            membershipRepo.save(m);
            added++;
        }
        log.info("[publisher-bootstrap] Publisher workspace '{}': {} staff member(s), {} newly added.",
                slug, staff.size(), added);
    }
}
