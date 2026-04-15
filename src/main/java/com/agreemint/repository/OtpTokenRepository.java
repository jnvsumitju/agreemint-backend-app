package com.agreemint.repository;

import com.agreemint.domain.OtpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OtpTokenRepository extends JpaRepository<OtpToken, UUID> {

    /** Find all non-expired, unused OTPs for a user (for validation). */
    List<OtpToken> findByUserIdAndUsedFalseAndExpiresAtAfter(UUID userId, Instant now);

    /** Check if an OTP was sent recently (rate limiting). */
    boolean existsByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    @Modifying
    @Query("DELETE FROM OtpToken t WHERE t.expiresAt < :now OR t.used = true")
    int deleteExpiredOrUsed(Instant now);
}
