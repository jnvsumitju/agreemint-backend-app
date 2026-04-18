package com.agreemint.repository;

import com.agreemint.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByOrgId(UUID orgId);

    List<Template> findByOwnerId(UUID ownerId);

    /** All templates belonging to an org, plus legacy unowned templates. */
    List<Template> findByOrgIdOrOrgIdIsNull(UUID orgId);

    /** Templates assigned to a specific product. Used by the Templates
     *  page's product filter. */
    List<Template> findByProductIdOrderByCreatedAtDesc(UUID productId);
}
