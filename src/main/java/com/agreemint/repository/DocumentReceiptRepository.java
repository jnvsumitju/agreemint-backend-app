package com.agreemint.repository;

import com.agreemint.domain.DocumentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentReceiptRepository extends JpaRepository<DocumentReceipt, UUID> {

    /**
     * The verification lookup.
     *
     * <p>Returns a list rather than an {@code Optional} because the digest is
     * indexed but not unique — see V26 for why a UNIQUE constraint would trade
     * a real availability risk for a theoretical one. Two rows here would mean
     * two byte-identical PDFs, which is a perfectly fine thing to confirm; the
     * caller reports the earliest issuance.
     */
    List<DocumentReceipt> findBySha256OrderByIssuedAtAsc(String sha256);

    Optional<DocumentReceipt> findFirstByDocumentIdOrderByIssuedAtAsc(UUID documentId);

    /** Lookup for the printed code. Unique by V27's partial index. */
    Optional<DocumentReceipt> findByVerificationCode(String verificationCode);
}
