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
}
