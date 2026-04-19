package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.FeatureFlag;
import com.agreemint.admin.domain.FeatureFlagOverride;
import com.agreemint.admin.repository.FeatureFlagOverrideRepository;
import com.agreemint.admin.repository.FeatureFlagRepository;
import com.agreemint.domain.Organization;
import com.agreemint.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for {@link FeatureFlag} rows + per-org overrides. A flag is the
 * "did we ship this?" atomic — the runtime editor reads its own subset
 * (currently not wired; /api/flags for that client-side would come next).
 */
@Tag(name = "Admin · Feature Flags")
@RestController
@RequestMapping("/api/admin/feature-flags")
public class AdminFeatureFlagController {

    private final FeatureFlagRepository flagRepo;
    private final FeatureFlagOverrideRepository overrideRepo;
    private final OrganizationRepository orgRepo;

    public AdminFeatureFlagController(
            FeatureFlagRepository flagRepo,
            FeatureFlagOverrideRepository overrideRepo,
            OrganizationRepository orgRepo) {
        this.flagRepo = flagRepo;
        this.overrideRepo = overrideRepo;
        this.orgRepo = orgRepo;
    }

    @GetMapping
    public List<AdminDtos.FeatureFlagResponse> list() {
        Map<UUID, String> orgNames = new HashMap<>();
        for (Organization o : orgRepo.findAll()) orgNames.put(o.getId(), o.getName());

        return flagRepo.findAll().stream()
                .map(f -> new AdminDtos.FeatureFlagResponse(
                        f.getKey(), f.getDescription(), f.isDefaultEnabled(),
                        overrideRepo.findByFlagKey(f.getKey()).stream()
                                .map(o -> new AdminDtos.FeatureFlagOverrideResponse(
                                        o.getOrgId(), orgNames.get(o.getOrgId()), o.isEnabled()))
                                .toList()))
                .toList();
    }

    /** Create or update a flag by key. `key` in the body is authoritative. */
    @PutMapping
    public AdminDtos.FeatureFlagResponse upsert(@RequestBody AdminDtos.FeatureFlagUpsertRequest req) {
        FeatureFlag f = flagRepo.findById(req.key()).orElseGet(() -> {
            FeatureFlag n = new FeatureFlag();
            n.setKey(req.key());
            return n;
        });
        f.setDescription(req.description());
        f.setDefaultEnabled(req.defaultEnabled());
        f.setUpdatedAt(Instant.now());
        flagRepo.save(f);
        return new AdminDtos.FeatureFlagResponse(
                f.getKey(), f.getDescription(), f.isDefaultEnabled(), List.of());
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        flagRepo.deleteById(key);
        // Overrides cascade via FK ON DELETE CASCADE.
        return ResponseEntity.noContent().build();
    }

    /** Set / clear a per-org override. `enabled=null` removes the override. */
    @PostMapping("/{key}/overrides")
    public ResponseEntity<Void> upsertOverride(
            @PathVariable String key,
            @RequestBody AdminDtos.FeatureFlagOverrideRequest req) {
        FeatureFlagOverride o = overrideRepo
                .findById(new FeatureFlagOverride.PK(key, req.orgId()))
                .orElseGet(() -> {
                    FeatureFlagOverride n = new FeatureFlagOverride();
                    n.setFlagKey(key);
                    n.setOrgId(req.orgId());
                    return n;
                });
        o.setEnabled(req.enabled());
        overrideRepo.save(o);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{key}/overrides/{orgId}")
    public ResponseEntity<Void> deleteOverride(@PathVariable String key, @PathVariable UUID orgId) {
        overrideRepo.deleteById(new FeatureFlagOverride.PK(key, orgId));
        return ResponseEntity.noContent().build();
    }
}
