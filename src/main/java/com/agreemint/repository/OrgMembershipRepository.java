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

    /**
     * Eager-fetch the user for callers that read {@code membership.getUser()}
     * outside a transaction.
     *
     * <p>{@code open-in-view} is off, so each repository call closes its own
     * session. The plain finder above is safe only inside {@code @Transactional}
     * — every other caller of it is. The admin org-detail endpoint is not, and
     * touching {@code getUser()} on a detached LAZY proxy there throws
     * {@code LazyInitializationException} and surfaces as a 500.
     */
    @EntityGraph(attributePaths = {"user"})
    List<OrgMembership> findWithUserByOrganizationId(UUID orgId);

    Optional<OrgMembership> findByUserIdAndOrganizationId(UUID userId, UUID orgId);

    boolean existsByUserIdAndOrganizationId(UUID userId, UUID orgId);

    /** Pessimistic lock on admin count — prevents race condition on last-admin removal. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM OrgMembership m WHERE m.organization.id = :orgId AND m.role = com.agreemint.domain.OrgRole.ADMIN")
    List<OrgMembership> findAdminsForUpdate(@Param("orgId") UUID orgId);

    /**
     * Member counts for a batch of orgs — one query for the whole page.
     * Replaces a per-org findByOrganizationId().size() loop in the admin list.
     */
    @Query("""
            SELECT m.organization.id, COUNT(m)
            FROM OrgMembership m
            WHERE m.organization.id IN :orgIds
            GROUP BY m.organization.id
            """)
    List<Object[]> countByOrgIds(@Param("orgIds") java.util.Collection<UUID> orgIds);

    /** Org counts for a batch of users, for the admin user list. */
    @Query("""
            SELECT m.user.id, COUNT(m)
            FROM OrgMembership m
            WHERE m.user.id IN :userIds
            GROUP BY m.user.id
            """)
    List<Object[]> countByUserIds(@Param("userIds") java.util.Collection<UUID> userIds);
}
