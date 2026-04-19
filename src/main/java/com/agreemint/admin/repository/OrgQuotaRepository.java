package com.agreemint.admin.repository;

import com.agreemint.admin.domain.OrgQuota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrgQuotaRepository extends JpaRepository<OrgQuota, UUID> {
}
