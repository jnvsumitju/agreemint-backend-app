package com.agreemint.repository;

import com.agreemint.domain.AuthProvider;
import com.agreemint.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /** Admin user list: optional email/name substring, paged and sorted in the DB. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u FROM User u
            WHERE :q IS NULL
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    org.springframework.data.domain.Page<User> search(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}
