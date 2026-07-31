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
 * <p><strong>SpringTemplateEngine inside, TemplateEngine on the signature.</strong>
 * A plain {@link TemplateEngine} installs {@code StandardDialect}, whose
 * expression evaluator is OGNL — and {@code thymeleaf-spring6} excludes the
 * {@code ognl} artifact, so it is not on the classpath at all. Every override
 * containing a {@code ${...}} therefore threw at render time, which is every
 * useful override. {@code SpringTemplateEngine} evaluates through SpEL, which
 * is present.
 *
 * <p>But the <em>declared return type</em> must stay {@link TemplateEngine}.
 * Spring Boot's {@code ThymeleafAutoConfiguration} declares its own engine
 * {@code @ConditionalOnMissingBean(ISpringTemplateEngine.class)}, and conditions
 * are evaluated against a bean method's declared type. Returning
 * {@code SpringTemplateEngine} here made Boot back off, the auto-configured
 * {@code templateEngine} bean vanished, and {@code EmailService} — which needs
 * it to render the bundled classpath templates — failed to start with two
 * candidates and no match. Widening the signature keeps the auto-configured
 * engine while still giving these two SpEL.
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
    public TemplateEngine stringTemplateEngine() {
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
    public TemplateEngine subjectTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver(TemplateMode.TEXT));
        return engine;
    }
}
