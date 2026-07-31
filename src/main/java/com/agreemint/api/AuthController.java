package com.agreemint.api;

import com.agreemint.api.dto.*;
import com.agreemint.config.OAuthProperties;
import com.agreemint.security.UserPrincipal;
import com.agreemint.admin.service.ImpersonationSessionService;
import com.agreemint.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Authentication", description = "Register, login, password reset, OTP, and email verification")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OAuthProperties oauthProps;
    private final ImpersonationSessionService impersonationSessions;
    private final com.agreemint.service.ActivityService activityService;

    public AuthController(AuthService authService, OAuthProperties oauthProps,
                          ImpersonationSessionService impersonationSessions,
                          com.agreemint.service.ActivityService activityService) {
        this.authService = authService;
        this.oauthProps = oauthProps;
        this.impersonationSessions = impersonationSessions;
        this.activityService = activityService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        AuthResponse response = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthResponse response = authService.login(req);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        AuthResponse response = authService.refreshTokens(req.refreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.ok().build();
    }

    /** Public: tells the frontend which OAuth providers are available and configured. */
    @GetMapping("/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(oauthProps.isGoogleReady(), oauthProps.isGithubReady());
    }

    public record ProvidersResponse(boolean google, boolean github) {}

    @GetMapping("/me")
    public ResponseEntity<AuthService.MeResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        // Same reason as endOwnImpersonation: permitAll route, null principal.
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // Resolved from the live session registry, not from the token's claim:
        // a revoked session must stop reporting itself as live, and only the
        // Redis TTL knows that.
        AuthService.Impersonation impersonation = null;
        if (principal.isImpersonated()) {
            ImpersonationSessionService.Session s =
                    impersonationSessions.find(principal.impersonationSid());
            if (s != null) {
                impersonation = new AuthService.Impersonation(
                        s.sessionId(), s.operatorId(), s.operatorEmail(),
                        s.orgId(), s.secondsRemaining());
            }
        }
        return ResponseEntity.ok(authService.getMe(principal.userId(), impersonation));
    }

    /**
     * End the impersonation session the caller is currently running in.
     *
     * <p>Lives here rather than under {@code /api/admin/**} because the caller
     * is the impersonated session itself, and its token carries
     * {@code isStaff=false} — the admin revoke endpoint is closed to it by
     * design. Without this, the console's "End session" button only cleared the
     * browser tab: the Redis session stayed live, kept appearing in the staff
     * sessions list, and the token remained usable until its TTL ran out.
     *
     * <p>Only ever ends the caller's own session; the session id comes from the
     * verified token, never from the request.
     */
    @DeleteMapping("/impersonation")
    public ResponseEntity<Void> endOwnImpersonation(@AuthenticationPrincipal UserPrincipal principal) {
        // principal is null for an anonymous caller: /api/auth/** is permitAll,
        // so this route is reachable with no Authorization header at all and the
        // resolver injects null rather than rejecting. Without this check the
        // next line NPEs into a logged 500 for anyone who probes the URL.
        if (principal == null || !principal.isImpersonated()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ImpersonationSessionService.Session ended =
                impersonationSessions.revoke(principal.impersonationSid());
        if (ended == null) return ResponseEntity.notFound().build();

        // Symmetric with the admin revoke path. This is now the ordinary way a
        // session finishes, so without it most sessions would show an
        // impersonate.start with no matching end — a reviewer could not tell how
        // long the operator was in the customer's workspace.
        if (ended.orgId() != null) {
            activityService.log(
                    ended.orgId(), ended.operatorId(), ended.operatorEmail(),
                    "impersonate.end", "User", ended.targetUserId(), ended.targetEmail());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Email Verification ──

    @GetMapping("/verify-email")
    public AuthResponse verifyEmail(@RequestParam String token) {
        return authService.verifyEmail(token);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody ResendVerificationRequest req) {
        authService.resendVerificationEmail(req.email());
        return ResponseEntity.ok().build();
    }

    public record ResendVerificationRequest(String email) {}

    /**
     * Public: resolve an invite token → narrow summary for the register page.
     * Lets the UI pre-fill the email field and lock it so the recipient can't
     * accidentally register with a different address than the one invited.
     */
    @GetMapping("/invitations/resolve")
    public InvitationResolveResponse resolveInvitation(@RequestParam String token) {
        return authService.resolveInvitation(token);
    }

    // ── OTP Login ──

    @PostMapping("/send-otp")
    public ResponseEntity<Void> sendOtp(@Valid @RequestBody SendOtpRequest req) {
        authService.sendOtp(req.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        AuthResponse response = authService.verifyOtp(req.email(), req.code());
        return ResponseEntity.ok(response);
    }

    public record SendOtpRequest(@jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email) {}
    public record VerifyOtpRequest(
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email,
            @jakarta.validation.constraints.NotBlank String code
    ) {}
}
