package com.agreemint.admin.repository;

import com.agreemint.admin.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
    List<Announcement> findByActiveTrueOrderByCreatedAtDesc();
}
