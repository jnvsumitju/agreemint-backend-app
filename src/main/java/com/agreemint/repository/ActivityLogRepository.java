package com.agreemint.repository;

import com.agreemint.domain.ActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    List<ActivityLog> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);
}
