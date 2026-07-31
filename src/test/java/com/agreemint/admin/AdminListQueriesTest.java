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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the admin org/user list queries.
 *
 * <p>Guards two regressions: the user list used to load every row, filter in
 * memory and truncate to a fixed 200 — so a match past that cut-off was
 * invisible — and the counts on both lists came from a query per row despite a
 * comment claiming they were batched.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AdminListQueriesTest {

    @Autowired private OrganizationRepository orgRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private OrgMembershipRepository membershipRepo;

    private Organization org(String name, String slug, OrgPlan plan) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(slug);
        o.setPlan(plan);
        return orgRepo.save(o);
    }

    private User user(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setPasswordHash("x");
        return userRepo.save(u);
    }

    private void join(User u, Organization o) {
        OrgMembership m = new OrgMembership();
        m.setUser(u);
        m.setOrganization(o);
        m.setRole(OrgRole.ADMIN);
        membershipRepo.save(m);
    }

    @BeforeEach
    void seed() {
        membershipRepo.deleteAll();
        userRepo.deleteAll();
        orgRepo.deleteAll();
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] r : rows) out.put((UUID) r[0], ((Number) r[1]).longValue());
        return out;
    }

    // ── Org list ──

    @Test
    void orgSearchMatchesNameAndSlugCaseInsensitively() {
        org("Acme Corp", "acme-corp", OrgPlan.PRO);
        org("Globex", "globex", OrgPlan.FREE);

        var pageable = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
        assertEquals(2, orgRepo.search("", pageable).getTotalElements(), "an empty q returns all — null is not a legal argument");
        assertEquals(1, orgRepo.search("acme", pageable).getTotalElements(), "matches name");
        assertEquals(1, orgRepo.search("GLOBEX", pageable).getTotalElements(), "case-insensitive");
        assertEquals(1, orgRepo.search("acme-c", pageable).getTotalElements(), "matches slug");
        assertEquals(0, orgRepo.search("nope", pageable).getTotalElements());
    }

    @Test
    void orgListIsPagedInTheDatabase() {
        for (int i = 0; i < 25; i++) org("Org " + i, "org-" + i, OrgPlan.FREE);

        Page<Organization> first = orgRepo.search("",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(10, first.getContent().size(), "page honours the requested size");
        assertEquals(25, first.getTotalElements(), "total reflects all matches, not the page");
        assertEquals(3, first.getTotalPages());
    }

    @Test
    void memberCountsComeBackGroupedForABatchOfOrgs() {
        Organization a = org("A", "a", OrgPlan.FREE);
        Organization b = org("B", "b", OrgPlan.FREE);
        Organization empty = org("C", "c", OrgPlan.FREE);

        join(user("one@x.com", "One"), a);
        join(user("two@x.com", "Two"), a);
        join(user("three@x.com", "Three"), b);

        Map<UUID, Long> byOrg = counts(membershipRepo.countByOrgIds(
                List.of(a.getId(), b.getId(), empty.getId())));

        assertEquals(2L, byOrg.get(a.getId()));
        assertEquals(1L, byOrg.get(b.getId()));
        // An org with no members is simply absent — callers default it to 0.
        assertNull(byOrg.get(empty.getId()), "orgs with no members are omitted, not zero rows");
    }

    // ── User list ──

    @Test
    void userSearchMatchesEmailAndName() {
        user("alice@example.com", "Alice Smith");
        user("bob@other.com", "Bob Jones");

        var pageable = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
        assertEquals(2, userRepo.search("", pageable).getTotalElements());
        assertEquals(1, userRepo.search("alice", pageable).getTotalElements(), "matches email");
        assertEquals(1, userRepo.search("Jones", pageable).getTotalElements(), "matches name");
        assertEquals(1, userRepo.search("EXAMPLE.COM", pageable).getTotalElements(), "case-insensitive");
    }

    @Test
    void userMatchBeyondTheOldTwoHundredCutoffIsStillFound() {
        // The regression: the list loaded everything, sorted, then took the
        // first 200 — a match sorted past that point could never be seen.
        for (int i = 0; i < 210; i++) user("filler" + i + "@x.com", "Filler " + i);
        user("needle@findme.com", "Needle");

        Page<User> result = userRepo.search("needle",
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getTotalElements(), "the match must be found regardless of position");
        assertEquals("needle@findme.com", result.getContent().get(0).getEmail());
    }

    @Test
    void orgCountsComeBackGroupedForABatchOfUsers() {
        User multi = user("multi@x.com", "Multi");
        User single = user("single@x.com", "Single");
        user("none@x.com", "None");

        join(multi, org("A", "a", OrgPlan.FREE));
        join(multi, org("B", "b", OrgPlan.FREE));
        join(single, org("C", "c", OrgPlan.FREE));

        Map<UUID, Long> byUser = counts(membershipRepo.countByUserIds(
                List.of(multi.getId(), single.getId())));

        assertEquals(2L, byUser.get(multi.getId()));
        assertEquals(1L, byUser.get(single.getId()));
    }
}
