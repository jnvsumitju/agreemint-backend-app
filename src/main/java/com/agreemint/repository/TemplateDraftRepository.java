package com.agreemint.repository;

import com.agreemint.domain.TemplateDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TemplateDraftRepository extends JpaRepository<TemplateDraft, UUID> {
}
