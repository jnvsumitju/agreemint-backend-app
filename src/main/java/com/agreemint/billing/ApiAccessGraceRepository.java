package com.agreemint.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiAccessGraceRepository extends JpaRepository<ApiAccessGrace, UUID> {

    /** Grace periods that have run out and whose keys still need revoking. */
    List<ApiAccessGrace> findByRevokedAtIsNullAndLapsedAtBefore(Instant cutoff);

    /** Still inside the grace window and not yet warned. */
    List<ApiAccessGrace> findByRevokedAtIsNullAndWarnedAtIsNull();
}
