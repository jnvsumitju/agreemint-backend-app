package com.agreemint.service;

import com.agreemint.api.dto.*;
import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.*;
import com.agreemint.repository.*;
import com.agreemint.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final OrgInvitationRepository invitationRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordResetTokenRepository resetTokenRepo;
    private final EmailVerificationTokenRepository verificationTokenRepo;
    private final OtpTokenRepository otpTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;
    private final SlugService slugService;

    @Value("${agreemint.otp.length:6}")
    private int otpLength;

    @Value("${agreemint.otp.ttl-minutes:10}")
    private int otpTtlMinutes;

    public AuthService(
            UserRepository userRepo,
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            OrgInvitationRepository invitationRepo,
            RefreshTokenRepository refreshTokenRepo,
            PasswordResetTokenRepository resetTokenRepo,
            EmailVerificationTokenRepository verificationTokenRepo,
            OtpTokenRepository otpTokenRepo,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            EmailService emailService,
            FrontendProperties frontendProps,
            SlugService slugService
    ) {
        this.userRepo = userRepo;
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.invitationRepo = invitationRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.resetTokenRepo = resetTokenRepo;
        this.verificationTokenRepo = verificationTokenRepo;
        this.otpTokenRepo = otpTokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
        this.slugService = slugService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        // Check if registering via a valid invite token
        boolean viaInvite = false;
        if (req.inviteToken() != null && !req.inviteToken().isBlank()) {
            OrgInvitation tokenInv = invitationRepo.findByToken(req.inviteToken().trim()).orElse(null);
            if (tokenInv != null && tokenInv.isPending()
                    && tokenInv.getEmail().equalsIgnoreCase(req.email().trim())) {
                viaInvite = true;
            }
        }

        // Create user
        User user = new User();
        user.setEmail(req.email().toLowerCase().trim());
        user.setName(req.name().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setProvider(AuthProvider.LOCAL);
        user.setEmailVerified(viaInvite);
        user = userRepo.save(user);

        // Decide whether to spin up a personal workspace for this user.
        //
        // Rule: if the user has *any* non-expired pending invitation to an
        // existing organisation (reached here via email — the inviteToken is
        // one path, but an admin may have sent an invite without the user
        // clicking the link), they should NOT get a personal workspace
        // auto-created. They only belong to the org(s) that invited them.
        // Admins in those orgs can create additional workspaces for the user
        // later if they want.
        //
        // If no pending invite exists for this email, the user is a fresh
        // self-signup and gets the usual "<Name>'s Workspace" created with
        // themselves as ADMIN.
        List<OrgInvitation> pendingInvites = invitationRepo.findByEmailAndAcceptedAtIsNull(user.getEmail());
        boolean hasValidPendingInvite = pendingInvites.stream()
                .anyMatch(inv -> !inv.isExpired() && orgRepo.existsById(inv.getOrgId()));

        Organization personalOrg = null;
        if (!hasValidPendingInvite) {
            String slug = slugService.generateUniqueSlug(req.name());
            personalOrg = new Organization();
            personalOrg.setName(req.name().trim() + "'s Workspace");
            personalOrg.setSlug(slug);
            personalOrg.setPlan(OrgPlan.FREE);
            personalOrg = orgRepo.save(personalOrg);

            OrgMembership adminMembership = new OrgMembership();
            adminMembership.setUser(user);
            adminMembership.setOrganization(personalOrg);
            adminMembership.setRole(OrgRole.ADMIN);
            membershipRepo.save(adminMembership);
        }

        // Accept any pending org invitations for this email
        Organization primaryInviteOrg = null;
        OrgRole primaryInviteRole = null;
        for (OrgInvitation inv : pendingInvites) {
            if (inv.isExpired()) continue;
            Organization invOrg = orgRepo.findById(inv.getOrgId()).orElse(null);
            if (invOrg == null) continue;
            if (membershipRepo.existsByUserIdAndOrganizationId(user.getId(), inv.getOrgId())) continue;

            OrgMembership invMembership = new OrgMembership();
            invMembership.setUser(user);
            invMembership.setOrganization(invOrg);
            invMembership.setRole(inv.getRole());
            membershipRepo.save(invMembership);

            inv.setAcceptedAt(Instant.now());
            invitationRepo.save(inv);

            // Prefer the token-matched invitation as the active org
            if (viaInvite && req.inviteToken() != null
                    && req.inviteToken().trim().equals(inv.getToken())) {
                primaryInviteOrg = invOrg;
                primaryInviteRole = inv.getRole();
            } else if (primaryInviteOrg == null) {
                primaryInviteOrg = invOrg;
                primaryInviteRole = inv.getRole();
            }
        }

        // Send email verification (skip if registered via invite — email already trusted)
        if (!viaInvite) {
            sendVerificationEmail(user);
            // Return a "verification required" response — no tokens, user cannot act yet
            return new AuthResponse(null, null, UserResponse.from(user), null, null, true);
        }

        // If registered via invite, return the invited org as the active one (with invited role)
        if (primaryInviteOrg != null) {
            return buildAuthResponse(user, primaryInviteOrg, primaryInviteRole);
        }
        // Defensive fallback — viaInvite implied an invitation existed, but if it
        // couldn't be resolved for any reason, still return a usable session.
        if (personalOrg != null) {
            return buildAuthResponse(user, personalOrg, OrgRole.ADMIN);
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Registration completed without any organization assignment");
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

        if (!user.isEmailVerified() && user.getProvider() == AuthProvider.LOCAL) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Please verify your email before signing in. Check your inbox for the verification link.");
        }

        // Pick the first org (with eager fetch — avoids N+1)
        OrgMembership membership = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElse(null);

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

        // No rotation: reuse the same refresh token until it expires. Rotating
        // on every refresh used to race across tabs / WebSocket reconnect / a
        // burst of concurrent requests after idle, where one request would
        // arrive with the just-rotated-away token and trigger a forced logout.
        // For a SPA holding the RT in localStorage, rotation isn't a real
        // security gain (XSS would grab the token regardless), so the UX cost
        // outweighed the benefit.
        User user = stored.getUser();
        OrgMembership membership = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElse(null);

        Organization org = membership != null ? membership.getOrganization() : null;
        OrgRole role = membership != null ? membership.getRole() : null;

        UUID orgId = org != null ? org.getId() : null;
        String accessToken = jwtService.generateAccessToken(user, orgId, role);
        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                UserResponse.from(user),
                org != null ? OrgResponse.from(org) : null,
                role != null ? role.name() : null
        );
    }

    /**
     * Public: resolve a pending org invitation by its token. Returns a narrow,
     * non-sensitive summary (email, org name, inviter name, role) so the
     * registration page can pre-fill and lock the email field.
     *
     * <p>Returns 404 if the token is not recognised. Returns {@code expired=true}
     * when the token is found but no longer valid — the UI can surface a
     * dedicated error rather than a generic 404.
     */
    @Transactional(readOnly = true)
    public InvitationResolveResponse resolveInvitation(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
        }
        OrgInvitation inv = invitationRepo.findByToken(token.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

        Organization org = orgRepo.findById(inv.getOrgId()).orElse(null);
        String inviterName = inv.getInvitedBy() != null
                ? userRepo.findById(inv.getInvitedBy()).map(User::getName).orElse("A teammate")
                : "A teammate";

        return new InvitationResolveResponse(
                inv.getEmail(),
                org != null ? org.getName() : "",
                inviterName,
                inv.getRole().name(),
                inv.isExpired() || !inv.isPending()
        );
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

        String resetLink = frontendProps.getBaseUrl() + "/reset-password?token=" + rawToken;
        emailService.sendPasswordResetEmail(email, resetLink);
        log.info("Password reset requested for email={}", email);
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


    // ── Email Verification ──

    private void sendVerificationEmail(User user) {
        String rawToken = UUID.randomUUID().toString();

        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(sha256(rawToken));
        token.setExpiresAt(Instant.now().plusSeconds(86400)); // 24 hours
        verificationTokenRepo.save(token);

        String verifyLink = frontendProps.getBaseUrl() + "/verify-email?token=" + rawToken;
        emailService.sendEmailVerificationEmail(user.getEmail(), verifyLink);
    }

    @Transactional
    public AuthResponse verifyEmail(String rawToken) {
        String tokenHash = sha256(rawToken);
        EmailVerificationToken stored = verificationTokenRepo.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            verificationTokenRepo.delete(stored);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token expired");
        }

        User user = stored.getUser();
        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        verificationTokenRepo.deleteByUserId(user.getId());

        log.info("Email verified for userId={}", user.getId());

        // Log the user in automatically after successful verification
        OrgMembership membership = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElse(null);
        Organization org = membership != null ? membership.getOrganization() : null;
        OrgRole role = membership != null ? membership.getRole() : null;
        return buildAuthResponse(user, org, role);
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        User user = userRepo.findByEmail(email.toLowerCase().trim())
                .orElse(null);
        if (user == null || user.isEmailVerified()) return; // Don't reveal anything

        // Delete old tokens
        verificationTokenRepo.deleteByUserId(user.getId());
        sendVerificationEmail(user);
    }

    // ── OTP ──

    @Transactional
    public void sendOtp(String email) {
        User user = userRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account found with this email"));

        // Rate limit: 1 OTP per 60 seconds
        if (otpTokenRepo.existsByUserIdAndCreatedAtAfter(user.getId(), Instant.now().minusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another code");
        }

        // Generate OTP code
        String code = generateOtpCode(otpLength);

        OtpToken otpToken = new OtpToken();
        otpToken.setUser(user);
        otpToken.setCodeHash(sha256(code));
        otpToken.setExpiresAt(Instant.now().plusSeconds(otpTtlMinutes * 60L));
        otpTokenRepo.save(otpToken);

        emailService.sendOtpEmail(email, code, otpTtlMinutes);
        log.info("OTP sent to email={}", email);
    }

    @Transactional
    public AuthResponse verifyOtp(String email, String code) {
        User user = userRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or code"));

        String codeHash = sha256(code);
        List<OtpToken> validTokens = otpTokenRepo
                .findByUserIdAndUsedFalseAndExpiresAtAfter(user.getId(), Instant.now());

        OtpToken match = validTokens.stream()
                .filter(t -> t.getCodeHash().equals(codeHash))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired code"));

        match.setUsed(true);
        otpTokenRepo.save(match);

        // Mark email as verified (OTP proves email ownership)
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setUpdatedAt(Instant.now());
            userRepo.save(user);
        }

        // Return auth tokens (same as login)
        OrgMembership membership = membershipRepo.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElse(null);
        Organization org = membership != null ? membership.getOrganization() : null;
        OrgRole role = membership != null ? membership.getRole() : null;

        return buildAuthResponse(user, org, role);
    }

    private String generateOtpCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    // ── Helpers ──

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
