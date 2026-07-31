package com.agreemint.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two customer-facing notices exist because staff touching someone's
 * account is now something that person is told about. They are the only emails
 * whose entire purpose is disclosure, so a render failure would defeat them
 * completely — and it would be invisible, because the send is {@code @Async}
 * and deliberately swallowed so that a mail outage cannot block support.
 */
class NoticeTemplateRenderingTest {

    private final TemplateEngine engine = new EmailTemplateConfig().stringTemplateEngine();

    private String source(String key) throws Exception {
        try (var in = new ClassPathResource("templates/email/" + key + ".html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Context ctx(String detailLabel, String detail) {
        Context ctx = new Context();
        ctx.setVariable("headline", "Crixaa support signed in to your account");
        ctx.setVariable("summary", "A member of the support team signed in.");
        ctx.setVariable("orgName", "Bob's Workspace & Co");
        ctx.setVariable("occurredAt", "31 Jul 2026, 14:22 UTC");
        ctx.setVariable("detailLabel", detailLabel);
        ctx.setVariable("detail", detail);
        return ctx;
    }

    @Test
    void impersonationNoticeRenders() throws Exception {
        String out = engine.process(source("impersonation-notice"),
                ctx("Session length", "up to 15 minutes"));

        assertTrue(out.contains("signed in to your account"), out);
        assertTrue(out.contains("31 Jul 2026, 14:22 UTC"), "the customer needs to know when");
        assertTrue(out.contains("up to 15 minutes"), out);
        assertTrue(out.contains("Workspace"), "the workspace row should render: " + out);
    }

    @Test
    void dataExportNoticeRenders() throws Exception {
        String out = engine.process(source("data-export-notice"),
                ctx("Scope", "your whole workspace"));

        assertTrue(out.contains("your whole workspace"), out);
        assertTrue(out.contains("31 Jul 2026, 14:22 UTC"), out);
    }

    @Test
    void aMissingOrgNameDoesNotLeakTheWordNull() throws Exception {
        Context ctx = ctx("Scope", "your account");
        ctx.setVariable("orgName", null);

        // A scope=user export may have no resolvable workspace. The row is
        // omitted rather than rendering "null" at the customer.
        String out = engine.process(source("data-export-notice"), ctx);
        assertFalse(out.contains(">null<"), out);
        assertTrue(out.contains("your account"), out);
    }

    @Test
    void anOrgNameWithMarkupIsEscapedInTheBody() throws Exception {
        Context ctx = ctx("Scope", "your account");
        ctx.setVariable("orgName", "<script>alert(1)</script>");

        // Org names are customer-supplied and land in an HTML email.
        String out = engine.process(source("data-export-notice"), ctx);
        assertFalse(out.contains("<script>alert(1)</script>"), "must not be injected raw");
        assertTrue(out.contains("&lt;script&gt;"), out);
    }
}
