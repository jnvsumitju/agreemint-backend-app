package com.agreemint.repository;

import com.agreemint.domain.TemplateDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TemplateDraftRepository extends JpaRepository<TemplateDraft, UUID> {

    /** Which of these templates currently hold uncommitted editor changes. */
    @org.springframework.data.jpa.repository.Query(
            "select d.templateId from TemplateDraft d where d.templateId in :ids")
    java.util.List<java.util.UUID> findTemplateIdsWithDraft(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<java.util.UUID> ids);
}
