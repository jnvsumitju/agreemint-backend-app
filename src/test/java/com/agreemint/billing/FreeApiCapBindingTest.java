package com.agreemint.billing;

import com.agreemint.config.PlanLimitsProperties;
import com.agreemint.domain.OrgPlan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the real application.yml produces for the per-plan API ceilings.
 *
 * <p>Binds the shipped file rather than a fixture, because the thing worth
 * protecting is the FILE, not the class — {@link PlanLimitsProperties} is four
 * nullable Integers and behaves correctly whatever you put in it. Every bug
 * this guards against is a YAML edit.
 *
 * <p>Two invariants, and they pull in opposite directions.
 *
 * <p><b>Free must be a real number.</b> The pricing page and the developer docs
 * both say paid plans get a higher daily ceiling than free. That sentence was
 * false for as long as every field here was null: null falls back to
 * {@code ratelimit.org-daily-max} for all plans alike, so free and paid resolved
 * to an identical 10,000 while the site advertised a gap.
 *
 * <p><b>The other three must stay null.</b> Null is the mechanism, not an
 * oversight — it is what makes paid track the system default. Declaring
 * {@code starter-api-daily-max} in YAML would silently pin Starter to whatever
 * number was typed, and the claim breaks again in the other direction, more
 * quietly.
 *
 * <p>Not a {@code @SpringBootTest}: the full context needs R2 credentials and
 * Redis, and this question does not.
 */
class FreeApiCapBindingTest {

    private static PlanLimitsProperties bindShippedYaml() throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        // Resolves ${PLANS_FREE_API_DAILY_MAX:500} to its default, which is the
        // value a deployment without that env var actually runs with.
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("agreemint.plans", PlanLimitsProperties.class)
                .orElseGet(PlanLimitsProperties::new);
    }

    @Test
    void freeIsCappedAtFiveHundredRequestsADay() throws IOException {
        assertEquals(500, bindShippedYaml().apiDailyMaxFor(OrgPlan.FREE),
                "free-api-daily-max must bind; without it \"higher limits on paid plans\" is untrue");
    }

    @Test
    void paidPlansStillFallBackToTheSystemDefault() throws IOException {
        PlanLimitsProperties p = bindShippedYaml();
        assertNull(p.apiDailyMaxFor(OrgPlan.STARTER), "Starter must stay null — see the class note");
        assertNull(p.apiDailyMaxFor(OrgPlan.PRO), "Pro must stay null — see the class note");
        assertNull(p.apiDailyMaxFor(OrgPlan.ENTERPRISE), "Enterprise must stay null — see the class note");
    }

    @Test
    void noPdfCapIsConfiguredOnAnyPlan() throws IOException {
        // A separate lever, documented but not pulled. If someone pulls it, the
        // pricing copy needs a matching line — this is where they find out.
        PlanLimitsProperties p = bindShippedYaml();
        for (OrgPlan plan : OrgPlan.values()) {
            assertNull(p.pdfDailyMaxFor(plan), "unexpected PDF cap on " + plan);
        }
    }
}
