package com.agreemint.repository;

import com.agreemint.domain.OrgInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, UUID> {

    Optional<OrgInvitation> findByToken(String token);

    List<OrgInvitation> findByEmailAndAcceptedAtIsNull(String email);

    List<OrgInvitation> findByOrgIdAndAcceptedAtIsNull(UUID orgId);

    boolean existsByOrgIdAndEmailAndAcceptedAtIsNull(UUID orgId, String email);
}
