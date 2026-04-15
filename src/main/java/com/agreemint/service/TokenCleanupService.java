package com.agreemint.service;

import com.agreemint.repository.EmailVerificationTokenRepository;
import com.agreemint.repository.OtpTokenRepository;
import com.agreemint.repository.PasswordResetTokenRepository;
import com.agreemint.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically cleans up expired tokens to prevent table bloat.
 * Runs every hour.
 */
@Service
public class TokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordResetTokenRepository resetTokenRepo;
    private final EmailVerificationTokenRepository verificationTokenRepo;
    private final OtpTokenRepository otpTokenRepo;

    public TokenCleanupService(
            RefreshTokenRepository refreshTokenRepo,
            PasswordResetTokenRepository resetTokenRepo,
            EmailVerificationTokenRepository verificationTokenRepo,
            OtpTokenRepository otpTokenRepo
    ) {
        this.refreshTokenRepo = refreshTokenRepo;
        this.resetTokenRepo = resetTokenRepo;
        this.verificationTokenRepo = verificationTokenRepo;
        this.otpTokenRepo = otpTokenRepo;
    }

    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();

        int refreshDeleted = refreshTokenRepo.deleteByExpiresAtBefore(now);
        int verificationDeleted = verificationTokenRepo.deleteExpired(now);
        int otpDeleted = otpTokenRepo.deleteExpiredOrUsed(now);

        if (refreshDeleted > 0 || verificationDeleted > 0 || otpDeleted > 0) {
            log.info("Token cleanup: {} refresh, {} verification, {} OTP tokens removed",
                    refreshDeleted, verificationDeleted, otpDeleted);
        }
    }
}
