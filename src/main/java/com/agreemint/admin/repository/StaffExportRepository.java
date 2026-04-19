package com.agreemint.admin.repository;

import com.agreemint.admin.domain.StaffExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StaffExportRepository extends JpaRepository<StaffExport, UUID> {
    List<StaffExport> findTop50ByOrderByRequestedAtDesc();
}
