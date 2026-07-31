package com.agreemint.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the three-way Thymeleaf wiring, which broke the application at startup
 * once and was caught by nothing until deploy.
 *
 * <p>The app needs three engines: Boot's auto-configured {@code templateEngine}
 * for the bundled classpath emails, and two string engines for staff overrides.
 * Declaring the string engines as {@code SpringTemplateEngine} made
 * {@code ThymeleafAutoConfiguration} back off — its engine is
 * {@code @ConditionalOnMissingBean(ISpringTemplateEngine.class)} — so
 * {@code templateEngine} silently disappeared and {@code EmailService} failed to
 * start with two candidates and no match.
 *
 * <p>Compilation could not catch it and neither could the rendering tests, which
 * instantiate {@link EmailTemplateConfig} directly and never build a context.
 * This runs the real condition evaluation, without needing a database.
 */
class TemplateEngineWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ThymeleafAutoConfiguration.class))
            .withUserConfiguration(EmailTemplateConfig.class);

    @Test
    void allThreeEnginesExist() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // The one the bundled email templates are rendered with. Its absence
            // is the whole bug.
            assertThat(ctx).hasBean("templateEngine");
            assertThat(ctx).hasBean("stringTemplateEngine");
            assertThat(ctx).hasBean("subjectTemplateEngine");
        });
    }

    @Test
    void theAutoConfiguredEngineIsNotSuppressed() {
        runner.run(ctx -> {
            assertThat(ctx.getBeanNamesForType(TemplateEngine.class))
                    .as("declaring the string engines as SpringTemplateEngine silently removed this one")
                    .contains("templateEngine");
            assertThat(ctx.getBeanNamesForType(TemplateEngine.class)).hasSize(3);
        });
    }

    @Test
    void theThreeAreDistinctInstances() {
        runner.run(ctx -> {
            Object bundled = ctx.getBean("templateEngine");
            Object body = ctx.getBean("stringTemplateEngine");
            Object subject = ctx.getBean("subjectTemplateEngine");
            assertThat(bundled).isNotSameAs(body);
            assertThat(body).isNotSameAs(subject);
        });
    }

    @Test
    void theStringEnginesStillEvaluateExpressions() {
        runner.run(ctx -> {
            // Widening the return type must not cost SpEL — losing it is what
            // made every staff override un-renderable in the first place.
            TemplateEngine body = ctx.getBean("stringTemplateEngine", TemplateEngine.class);
            Context vars = new Context();
            vars.setVariable("otpCode", "482915");
            assertThat(body.process("<p th:text=\"${otpCode}\">x</p>", vars)).contains("482915");
        });
    }
}
