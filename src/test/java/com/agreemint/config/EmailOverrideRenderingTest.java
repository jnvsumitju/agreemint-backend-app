package com.agreemint.config;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof that a staff-authored email override can actually render.
 *
 * <p>It could not. {@code EmailTemplateConfig} built a plain
 * {@link TemplateEngine}, which installs {@code StandardDialect} and evaluates
 * {@code ${...}} through OGNL — and {@code thymeleaf-spring6} excludes the
 * {@code ognl} artifact, so it is absent from the classpath entirely. Every
 * override with an interpolation threw, which is every override worth writing.
 *
 * <p>This was also missed twice by inspection, because {@code spring-expression}
 * <em>is</em> present and it is easy to conclude SpEL is therefore in use. It is
 * not: SpEL only comes with {@code SpringTemplateEngine}. Hence a test that
 * renders rather than an argument that it should.
 */
class EmailOverrideRenderingTest {

    private final EmailTemplateConfig config = new EmailTemplateConfig();

    private Context ctx() {
        Context ctx = new Context();
        ctx.setVariable("otpCode", "482915");
        ctx.setVariable("documentTitle", "Bob's Contract & Addendum");
        return ctx;
    }

    @Test
    void anOverrideBodyInterpolatesVariables() {
        TemplateEngine engine = config.stringTemplateEngine();

        String out = engine.process(
                "<p>Your code is <b th:text=\"${otpCode}\">x</b></p>", ctx());

        assertTrue(out.contains("482915"), "the whole point of an override is live values: " + out);
    }

    @Test
    void anOverrideBodyInterpolatesWithInlineSyntaxToo() {
        TemplateEngine engine = config.stringTemplateEngine();
        String out = engine.process("<p>Code: [[${otpCode}]]</p>", ctx());
        assertTrue(out.contains("482915"), out);
    }

    @Test
    void aSubjectRendersUnescaped() {
        TemplateEngine engine = config.subjectTemplateEngine();

        String out = engine.process("Review [(${documentTitle})]", ctx());

        // HTML mode turned an apostrophe into &#39; in the inbox. A subject is
        // a plain string and must arrive as one.
        assertTrue(out.contains("Bob's Contract & Addendum"),
                "subject must not be HTML-escaped: " + out);
        assertFalse(out.contains("&#39;"), out);
        assertFalse(out.contains("&amp;"), out);
    }

    @Test
    void aMalformedOverrideThrowsSoTheCallerCanFallBack() {
        TemplateEngine engine = config.stringTemplateEngine();

        // EmailService catches this and sends the bundled template instead. The
        // guarantee is that a bad edit cannot silently drop a password reset.
        assertThrows(Exception.class,
                () -> engine.process("<p th:text=\"${ oops (\">x</p>", ctx()));
    }
}
