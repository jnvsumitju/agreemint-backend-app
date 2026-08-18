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

    /**
     * Highest version number per template, for a whole list in one query.
     *
     * <p>Per-template lookups would make the templates list N+1 — it is the
     * first screen after login and already fans out for product names.
     */
    @org.springframework.data.jpa.repository.Query(
            "select v.template.id, max(v.versionNumber) from TemplateVersion v "
                    + "where v.template.id in :ids group by v.template.id")
    java.util.List<Object[]> findMaxVersionByTemplateIds(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);

    boolean existsByTemplate_IdAndId(UUID templateId, UUID versionId);
}
