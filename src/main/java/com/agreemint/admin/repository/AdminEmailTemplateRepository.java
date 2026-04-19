package com.agreemint.admin.repository;

import com.agreemint.admin.domain.AdminEmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminEmailTemplateRepository extends JpaRepository<AdminEmailTemplate, String> {
}
