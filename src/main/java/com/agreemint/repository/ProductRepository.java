package com.agreemint.repository;

import com.agreemint.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByOrgIdOrderByNameAsc(UUID orgId);

    Optional<Product> findByOrgIdAndName(UUID orgId, String name);

    boolean existsByOrgIdAndName(UUID orgId, String name);
}
