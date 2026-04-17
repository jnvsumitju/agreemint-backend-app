package com.agreemint.api;

import com.agreemint.api.dto.*;
import com.agreemint.config.OAuthProperties;
import com.agreemint.security.UserPrincipal;
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

    public AuthController(AuthService authService, OAuthProperties oauthProps) {
        this.authService = authService;
        this.oauthProps = oauthProps;
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
        AuthService.MeResponse response = authService.getMe(principal.userId());
        return ResponseEntity.ok(response);
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
