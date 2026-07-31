package com.agreemint.admin;

import com.agreemint.admin.domain.FeatureFlag;
import com.agreemint.admin.domain.FeatureFlagOverride;
import com.agreemint.admin.repository.FeatureFlagOverrideRepository;
import com.agreemint.admin.repository.FeatureFlagRepository;
import com.agreemint.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for flag resolution — the behaviour that did not exist at all until
 * now. The repositories were written by the admin API and read by nothing, so
 * toggling a flag changed nothing in the product.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(FeatureFlagService.class)
class FeatureFlagResolutionTest {

    @Autowired private FeatureFlagRepository flagRepo;
    @Autowired private FeatureFlagOverrideRepository overrideRepo;
    @Autowired private FeatureFlagService flags;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private void flag(String key, boolean defaultEnabled) {
        FeatureFlag f = new FeatureFlag();
        f.setKey(key);
        f.setDescription("test flag");
        f.setDefaultEnabled(defaultEnabled);
        flagRepo.save(f);
    }

    private void override(String key, UUID orgId, boolean enabled) {
        FeatureFlagOverride o = new FeatureFlagOverride();
        o.setFlagKey(key);
        o.setOrgId(orgId);
        o.setEnabled(enabled);
        overrideRepo.save(o);
    }

    @BeforeEach
    void seed() {
        overrideRepo.deleteAll();
        flagRepo.deleteAll();
        flags.invalidateAll();
    }

    @Test
    void fallsBackToTheFlagDefault() {
        flag("new-editor", true);
        flag("beta-thing", false);
        flags.invalidateAll();

        assertTrue(flags.isEnabled("new-editor", orgA));
        assertFalse(flags.isEnabled("beta-thing", orgA));
    }

    @Test
    void perOrgOverrideWinsOverTheDefault() {
        flag("new-editor", false);
        override("new-editor", orgA, true);
        flags.invalidateAll();

        assertTrue(flags.isEnabled("new-editor", orgA), "orgA has an override turning it on");
        assertFalse(flags.isEnabled("new-editor", orgB), "orgB falls back to the default");
    }

    @Test
    void overrideCanAlsoTurnSomethingOff() {
        flag("new-editor", true);
        override("new-editor", orgA, false);
        flags.invalidateAll();

        assertFalse(flags.isEnabled("new-editor", orgA));
        assertTrue(flags.isEnabled("new-editor", orgB));
    }

    @Test
    void unknownKeyIsOff() {
        flag("known", true);
        flags.invalidateAll();

        // Fail closed: a typo must not enable a feature for everyone.
        assertFalse(flags.isEnabled("nonexistent", orgA));
        assertFalse(flags.isEnabled(null, orgA));
        assertFalse(flags.isEnabled("  ", orgA));
    }

    @Test
    void nullOrgResolvesToDefaultsOnly() {
        flag("new-editor", true);
        override("new-editor", orgA, false);
        flags.invalidateAll();

        assertTrue(flags.isEnabled("new-editor", null),
                "no org context means no override applies");
    }

    @Test
    void staleOverrideForADeletedFlagDoesNotResurrectIt() {
        flag("temp", true);
        override("temp", orgA, true);
        flags.invalidateAll();
        assertTrue(flags.isEnabled("temp", orgA));

        // Delete the flag but leave the override row behind, as would happen
        // without the FK cascade.
        flagRepo.deleteById("temp");
        flags.invalidateAll();

        assertFalse(flags.isEnabled("temp", orgA),
                "an override must not keep a deleted flag alive");
        assertFalse(flags.resolveAll(orgA).containsKey("temp"));
    }

    @Test
    void resolveAllReturnsEveryKnownFlag() {
        flag("a", true);
        flag("b", false);
        override("b", orgA, true);
        flags.invalidateAll();

        var resolved = flags.resolveAll(orgA);
        assertEquals(2, resolved.size());
        assertTrue(resolved.get("a"));
        assertTrue(resolved.get("b"), "override applied");
    }

    @Test
    void invalidateOrgDropsOnlyThatOrgsCache() {
        flag("f", false);
        flags.invalidateAll();
        assertFalse(flags.isEnabled("f", orgA));   // caches orgA
        assertFalse(flags.isEnabled("f", orgB));   // caches orgB

        override("f", orgA, true);
        flags.invalidateOrg(orgA);

        assertTrue(flags.isEnabled("f", orgA), "orgA re-reads and sees the override");
    }
}
