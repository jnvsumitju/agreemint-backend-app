package com.agreemint.repository;

import com.agreemint.domain.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {
}
