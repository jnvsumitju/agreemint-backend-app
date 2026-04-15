package com.agreemint.service;

import com.agreemint.api.dto.*;
import com.agreemint.domain.*;
import com.agreemint.repository.*;
import com.agreemint.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepo;
    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordResetTokenRepository resetTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepo,
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            RefreshTokenRepository refreshTokenRepo,
            PasswordResetTokenRepository resetTokenRepo,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepo = userRepo;
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.resetTokenRepo = resetTokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        // Create user
        User user = new User();
        user.setEmail(req.email().toLowerCase().trim());
        user.setName(req.name().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setProvider(AuthProvider.LOCAL);
        user.setEmailVerified(false);
        user = userRepo.save(user);

        // Create default personal org
        String slug = generateSlug(req.name());
        Organization org = new Organization();
        org.setName(req.name().trim() + "'s Workspace");
        org.setSlug(slug);
        org.setPlan(OrgPlan.FREE);
        org = orgRepo.save(org);

        // Make user ADMIN of their org
        OrgMembership membership = new OrgMembership();
        membership.setUser(user);
        membership.setOrganization(org);
        membership.setRole(OrgRole.ADMIN);
        membershipRepo.save(membership);

        return buildAuthResponse(user, org, OrgRole.ADMIN);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepo.findByEmail(req.email().toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (user.getPasswordHash() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This account uses " + user.getProvider().name() + " login. Please sign in with " + user.getProvider().name() + ".");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // Pick the first org (or null)
        OrgMembership membership = membershipRepo.findByUserId(user.getId())
                .stream().findFirst().orElse(null);

        Organization org = membership != null ? membership.getOrganization() : null;
        OrgRole role = membership != null ? membership.getRole() : null;

        return buildAuthResponse(user, org, role);
    }

    @Transactional
    public AuthResponse refreshTokens(String rawRefreshToken) {
        var claims = jwtService.extractClaimsOrNull(rawRefreshToken);
        if (claims == null || !"refresh".equals(claims.get("type"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String tokenHash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepo.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token not found"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepo.delete(stored);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        // Rotate: delete old, issue new
        refreshTokenRepo.delete(stored);

        User user = stored.getUser();
        OrgMembership membership = membershipRepo.findByUserId(user.getId())
                .stream().findFirst().orElse(null);

        Organization org = membership != null ? membership.getOrganization() : null;
        OrgRole role = membership != null ? membership.getRole() : null;

        return buildAuthResponse(user, org, role);
    }

    @Transactional
    public void forgotPassword(String email) {
        var userOpt = userRepo.findByEmail(email.toLowerCase().trim());
        if (userOpt.isEmpty()) return; // Don't reveal if email exists

        User user = userOpt.get();
        String rawToken = UUID.randomUUID().toString();

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(sha256(rawToken));
        token.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour
        resetTokenRepo.save(token);

        // TODO: Send email with reset link containing rawToken
        // For now, log it (development only)
        System.out.println("[DEV] Password reset token for " + email + ": " + rawToken);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);
        PasswordResetToken stored = resetTokenRepo.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token"));

        if (stored.isUsed() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
        }

        stored.setUsed(true);
        resetTokenRepo.save(stored);

        User user = stored.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
    }

    /** Get current user info with all their org memberships. */
    public record MeResponse(UserResponse user, List<MeOrgEntry> orgs) {}
    public record MeOrgEntry(OrgResponse org, String role) {}

    @Transactional(readOnly = true)
    public MeResponse getMe(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<OrgMembership> memberships = membershipRepo.findByUserId(userId);
        List<MeOrgEntry> orgs = memberships.stream()
                .map(m -> new MeOrgEntry(OrgResponse.from(m.getOrganization()), m.getRole().name()))
                .toList();

        return new MeResponse(UserResponse.from(user), orgs);
    }

    // ── Helpers ──

    private AuthResponse buildAuthResponse(User user, Organization org, OrgRole role) {
        UUID orgId = org != null ? org.getId() : null;
        String accessToken = jwtService.generateAccessToken(user, orgId, role);
        String refreshToken = jwtService.generateRefreshToken(user);

        // Persist refresh token hash
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setTokenHash(sha256(refreshToken));
        rt.setExpiresAt(Instant.now().plus(jwtService.getRefreshTokenExpiry()));
        refreshTokenRepo.save(rt);

        return new AuthResponse(
                accessToken,
                refreshToken,
                UserResponse.from(user),
                org != null ? OrgResponse.from(org) : null,
                role != null ? role.name() : null
        );
    }

    private String generateSlug(String name) {
        String base = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "workspace";
        String slug = base;
        int attempt = 0;
        while (orgRepo.existsBySlug(slug)) {
            slug = base + "-" + (++attempt);
        }
        return slug;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
