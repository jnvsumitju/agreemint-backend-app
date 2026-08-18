package com.agreemint.repository;

import com.agreemint.domain.AuthProvider;
import com.agreemint.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Every internal staff account — used to seed publisher-org membership. */
    java.util.List<User> findByStaffTrue();

    boolean existsByEmail(String email);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /**
     * Admin list search. {@code q} must never be null — pass "" for "no filter".
     *
     * <p>The obvious {@code :q IS NULL OR LOWER(col) LIKE ...} form fails on
     * Postgres: a null bind has no type context other than {@code LOWER(?)},
     * PgJDBC sends it untyped, the server infers {@code bytea}, and there is no
     * {@code lower(bytea)} — so the unfiltered list, which is the default view,
     * threw 500 while a search worked. Comparing against '' keeps the parameter
     * a real string, which is all Postgres needs to type it.
     *
     * <p>H2 in PostgreSQL mode accepts the null form, which is why the tests
     * were green and only production failed.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u FROM User u
            WHERE :q = ''
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    org.springframework.data.domain.Page<User> search(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}
