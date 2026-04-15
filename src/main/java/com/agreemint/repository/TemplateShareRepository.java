package com.agreemint.repository;

import com.agreemint.domain.TemplateShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateShareRepository extends JpaRepository<TemplateShare, UUID> {

    List<TemplateShare> findByTemplateId(UUID templateId);

    Optional<TemplateShare> findByShareToken(String shareToken);

    List<TemplateShare> findBySharedWithUserId(UUID userId);

    Optional<TemplateShare> findByTemplateIdAndSharedWithUserId(UUID templateId, UUID userId);

    void deleteByTemplateIdAndId(UUID templateId, UUID shareId);
}
