package com.agreemint.admin.api;

import java.util.HashMap;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import com.agreemint.admin.api.dto.PageResponse;
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
    public PageResponse<AdminDtos.UserSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int pageSize = Math.min(200, Math.max(1, size));
        // "" not null: a null bound into LOWER() has no type on Postgres and
        // the server infers bytea, so the unfiltered list 500s. See the repository.
        String search = (q == null || q.isBlank()) ? "" : q.trim();

        // Searched, sorted and paged in the DB. This previously loaded every
        // user, filtered in memory, then truncated to a fixed 200 — so a match
        // beyond that cut-off was simply invisible.
        Page<User> users = userRepo.search(search,
                PageRequest.of(Math.max(0, page), pageSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        List<UUID> userIds = users.getContent().stream().map(User::getId).toList();
        if (userIds.isEmpty()) {
            return PageResponse.of(users, List.of());
        }

        // One grouped query for the page, replacing a query per user.
        Map<UUID, Long> orgCounts = new HashMap<>();
        for (Object[] row : membershipRepo.countByUserIds(userIds)) {
            orgCounts.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        List<AdminDtos.UserSummary> items = users.getContent().stream()
                .map(u -> new AdminDtos.UserSummary(
                        u.getId(),
                        u.getEmail(),
                        u.getName(),
                        u.getCreatedAt(),
                        u.isEmailVerified(),
                        u.isStaff(),
                        orgCounts.getOrDefault(u.getId(), 0L).intValue()))
                .toList();

        return PageResponse.of(users, items);
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
                u.getCreatedAt(), u.isEmailVerified(), u.isStaff(), orgs));
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
