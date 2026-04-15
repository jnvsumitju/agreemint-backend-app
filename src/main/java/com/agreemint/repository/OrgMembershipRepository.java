package com.agreemint.repository;

import com.agreemint.domain.OrgMembership;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, UUID> {

    List<OrgMembership> findByUserId(UUID userId);

    /** Eager-fetch organization to avoid N+1 when listing user orgs. */
    @EntityGraph(attributePaths = {"organization"})
    List<OrgMembership> findWithOrgByUserId(UUID userId);

    /** Get first membership for a user (for login — avoids loading ALL memberships). */
    @EntityGraph(attributePaths = {"organization"})
    Optional<OrgMembership> findFirstByUserIdOrderByCreatedAtAsc(UUID userId);

    List<OrgMembership> findByOrganizationId(UUID orgId);

    Optional<OrgMembership> findByUserIdAndOrganizationId(UUID userId, UUID orgId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID orgId);

    /** Pessimistic lock on admin count — prevents race condition on last-admin removal. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM OrgMembership m WHERE m.organization.id = :orgId AND m.role = com.agreemint.domain.OrgRole.ADMIN")
    List<OrgMembership> findAdminsForUpdate(@Param("orgId") UUID orgId);
}
