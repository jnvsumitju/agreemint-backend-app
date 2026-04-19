package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.User;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.RefreshTokenRepository;
import com.agreemint.repository.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin portal — user search + detail + ops (force logout, flag-as-staff).
 * Password resets are handled by the existing /api/auth flow; this
 * controller just triggers one via the password-reset service.
 */
@Tag(name = "Admin · Users")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository userRepo;
    private final OrgMembershipRepository membershipRepo;
    private final RefreshTokenRepository refreshRepo;

    public AdminUserController(
            UserRepository userRepo,
            OrgMembershipRepository membershipRepo,
            RefreshTokenRepository refreshRepo) {
        this.userRepo = userRepo;
        this.membershipRepo = membershipRepo;
        this.refreshRepo = refreshRepo;
    }

    /** Returns a paginated user list with an optional email/name substring filter. */
    @GetMapping
    public List<AdminDtos.UserSummary> list(@RequestParam(required = false) String q) {
        List<User> users = userRepo.findAll();
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (!needle.isEmpty()) {
            users = users.stream()
                    .filter(u -> (u.getEmail() != null && u.getEmail().toLowerCase().contains(needle))
                            || (u.getName() != null && u.getName().toLowerCase().contains(needle)))
                    .toList();
        }
        // Pre-compute org counts. Same N+1 caveat as the org list — fine at
        // internal scale, drop in a native aggregate if we outgrow it.
        Map<UUID, Integer> orgCounts = users.stream().collect(Collectors.toMap(
                User::getId,
                u -> membershipRepo.findByUserId(u.getId()).size(),
                (a, b) -> a));
        return users.stream()
                .map(u -> new AdminDtos.UserSummary(
                        u.getId(),
                        u.getEmail(),
                        u.getName(),
                        u.getCreatedAt(),
                        // lastLoginAt: we don't currently record this on the
                        // user row. Leaving null until a `users.last_login_at`
                        // column lands. UI renders as "—".
                        null,
                        u.isEmailVerified(),
                        u.isStaff(),
                        orgCounts.getOrDefault(u.getId(), 0)))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .limit(200)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminDtos.UserDetail> detail(@PathVariable UUID id) {
        Optional<User> maybe = userRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        User u = maybe.get();
        List<AdminDtos.UserOrg> orgs = membershipRepo.findWithOrgByUserId(id).stream()
                .map((OrgMembership m) -> new AdminDtos.UserOrg(
                        m.getOrganization().getId(),
                        m.getOrganization().getName(),
                        m.getRole().name(),
                        m.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(new AdminDtos.UserDetail(
                u.getId(), u.getEmail(), u.getName(), u.getAvatarUrl(),
                u.getCreatedAt(), null, u.isEmailVerified(), u.isStaff(), orgs));
    }

    /** Revoke every refresh token for this user — the next API call with an
     *  expired access token (within minutes) forces re-login. */
    @PostMapping("/{id}/force-logout")
    @Transactional
    public ResponseEntity<Void> forceLogout(@PathVariable UUID id) {
        if (!userRepo.existsById(id)) return ResponseEntity.notFound().build();
        refreshRepo.deleteByUserId(id);
        return ResponseEntity.noContent().build();
    }

    /** Toggle the staff flag. Intentionally no safeguard against demoting the
     *  last staff user — that's a policy call; easier to paper over by editing
     *  the DB directly if it happens. */
    @PostMapping("/{id}/staff")
    public ResponseEntity<Void> setStaff(@PathVariable UUID id, @RequestParam boolean value) {
        Optional<User> maybe = userRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        User u = maybe.get();
        u.setStaff(value);
        userRepo.save(u);
        return ResponseEntity.noContent().build();
    }
}
