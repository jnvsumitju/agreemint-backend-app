package com.agreemint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Thymeleaf engines that treat their input as the template itself rather than
 * as a template name.
 *
 * <p>Needed for staff-authored email overrides: those are stored as HTML in
 * {@code admin_email_templates.body_html}, and rendering them through the
 * ordinary engine would look for a file on the classpath by that name.
 *
 * <p><strong>SpringTemplateEngine, not TemplateEngine.</strong> A plain
 * {@link TemplateEngine} installs {@code StandardDialect}, whose expression
 * evaluator is OGNL — and {@code thymeleaf-spring6} explicitly excludes the
 * {@code ognl} artifact, so it is not on the classpath at all. Every override
 * containing a {@code ${...}} therefore threw at render time, which is to say
 * every useful override: an email body with no interpolation is not worth
 * overriding. {@code SpringTemplateEngine} installs
 * {@code SpringStandardDialect} and evaluates through SpEL, which is present.
 */
@Configuration
public class EmailTemplateConfig {

    private static StringTemplateResolver resolver(TemplateMode mode) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(mode);
        // Staff edit these live; caching would hide their changes.
        resolver.setCacheable(false);
        return resolver;
    }

    /** Renders an override body. HTML mode, because the body is HTML. */
    @Bean
    public SpringTemplateEngine stringTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver(TemplateMode.HTML));
        return engine;
    }

    /**
     * Renders an override <em>subject</em>.
     *
     * <p>Separate engine in TEXT mode. A subject is a plain string, and running
     * it through the HTML engine escaped every interpolated value — an
     * apostrophe in a document title arrived in the inbox as
     * {@code &amp;#39;}.
     */
    @Bean
    public SpringTemplateEngine subjectTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver(TemplateMode.TEXT));
        return engine;
    }
}
