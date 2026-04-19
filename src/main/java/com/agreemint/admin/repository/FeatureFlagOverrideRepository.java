package com.agreemint.admin.repository;

import com.agreemint.admin.domain.FeatureFlagOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeatureFlagOverrideRepository
        extends JpaRepository<FeatureFlagOverride, FeatureFlagOverride.PK> {
    List<FeatureFlagOverride> findByFlagKey(String flagKey);
    List<FeatureFlagOverride> findByOrgId(UUID orgId);
}
