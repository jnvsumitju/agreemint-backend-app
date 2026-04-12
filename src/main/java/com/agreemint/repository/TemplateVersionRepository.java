package com.agreemint.repository;

import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, UUID> {

    List<TemplateVersion> findByTemplate_IdOrderByVersionNumberDesc(UUID templateId);

    Optional<TemplateVersion> findFirstByTemplateOrderByVersionNumberDesc(Template template);

    boolean existsByTemplate_IdAndId(UUID templateId, UUID versionId);
}
