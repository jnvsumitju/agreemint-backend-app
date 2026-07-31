package com.agreemint.service;

import com.agreemint.admin.repository.AdminEmailTemplateRepository;
import com.agreemint.admin.domain.AdminEmailTemplate;
import com.agreemint.config.EmailProperties;
import com.agreemint.config.ResendProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends transactional emails using Thymeleaf templates, delivered through the
 * Resend HTTP API (https://resend.com) — no SMTP. All methods are @Async so
 * they don't block the calling thread. If Resend is not configured (e.g. dev,
 * empty RESEND_API_KEY) the email is logged and skipped; send failures are
 * logged but never thrown, so a mail outage can't break the request flow.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateEngine templateEngine;
    private final TemplateEngine stringTemplateEngine;
    private final TemplateEngine subjectTemplateEngine;
    private final AdminEmailTemplateRepository overrideRepo;
    private final EmailProperties emailProps;
    private final ResendProperties resendProps;
    private final HttpClient http;

    public EmailService(
            // Explicit: this is Boot's auto-configured engine for the bundled
            // classpath templates, not either of the string engines. It used to
            // resolve by parameter name alone, which silently stopped working
            // the moment a second candidate appeared.
            @org.springframework.beans.factory.annotation.Qualifier("templateEngine")
            TemplateEngine templateEngine,
            EmailProperties emailProps, ResendProperties resendProps,
            @org.springframework.beans.factory.annotation.Qualifier("stringTemplateEngine") TemplateEngine stringTemplateEngine,
            @org.springframework.beans.factory.annotation.Qualifier("subjectTemplateEngine") TemplateEngine subjectTemplateEngine,
            AdminEmailTemplateRepository overrideRepo) {
        this.templateEngine = templateEngine;
        this.emailProps = emailProps;
        this.resendProps = resendProps;
        this.stringTemplateEngine = stringTemplateEngine;
        this.subjectTemplateEngine = subjectTemplateEngine;
        this.overrideRepo = overrideRepo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Send password reset email with a clickable link. */
    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        Context ctx = new Context();
        ctx.setVariable("resetLink", resetLink);
        ctx.setVariable("expiryMinutes", 60);

        sendTemplated("password-reset", to, "Reset your Crixaa password", ctx);
    }

    /**
     * Tell a customer that staff signed in to their account.
     *
     * <p>Sent on every session start. The audit trail records impersonation for
     * staff, but only staff can read it — without this the person whose account
     * was accessed had no way to find out. {@code @Async} and swallowed on
     * failure: a mail outage must not stop support from doing its job, and the
     * activity_log row is the durable record either way.
     */
    @Async
    public void sendImpersonationNoticeEmail(String to, String orgName, String occurredAt,
                                              int ttlMinutes) {
        Context ctx = new Context();
        ctx.setVariable("headline", "Crixaa support signed in to your account");
        ctx.setVariable("summary", "A member of the Crixaa support team signed in to your account "
                + "to investigate an issue. They can see and do what you can.");
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("occurredAt", occurredAt);
        ctx.setVariable("detailLabel", "Session length");
        ctx.setVariable("detail", "up to " + ttlMinutes + " minutes");
        sendTemplated("impersonation-notice", to,
                "Crixaa support signed in to your account", ctx);
    }

    /** Tell a customer that staff exported data from their account. */
    @Async
    public void sendDataExportNoticeEmail(String to, String orgName, String occurredAt,
                                           String scope) {
        Context ctx = new Context();
        ctx.setVariable("headline", "Crixaa support exported data from your account");
        ctx.setVariable("summary", "A member of the Crixaa support team exported a copy of data "
                + "associated with your account.");
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("occurredAt", occurredAt);
        ctx.setVariable("detailLabel", "Scope");
        ctx.setVariable("detail", scope);
        sendTemplated("data-export-notice", to,
                "Crixaa support exported data from your account", ctx);
    }

    /** Send email verification link after registration. */
    @Async
    public void sendEmailVerificationEmail(String to, String verifyLink) {
        Context ctx = new Context();
        ctx.setVariable("verifyLink", verifyLink);

        sendTemplated("email-verification", to, "Verify your email address", ctx);
    }

    /** Send OTP code for passwordless login. */
    @Async
    public void sendOtpEmail(String to, String otpCode, int ttlMinutes) {
        Context ctx = new Context();
        ctx.setVariable("otpCode", otpCode);
        ctx.setVariable("ttlMinutes", ttlMinutes);

        sendTemplated("otp-code", to, "Your Crixaa login code: " + otpCode, ctx);
    }

    /** Notify an approver they have a pending approval step. */
    @Async
    public void sendApprovalRequestEmail(String to, String documentTitle,
                                          String approverName, String reviewLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("approverName", approverName);
        ctx.setVariable("reviewLink", reviewLink);

        sendTemplated("approval-request", to, "Action required: Review '" + documentTitle + "'", ctx);
    }

    /** Notify document creator about approval/rejection decision. */
    @Async
    public void sendApprovalDecisionEmail(String to, String documentTitle, String decision,
                                           String reviewerName, String comment, String documentLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("decision", decision);
        ctx.setVariable("reviewerName", reviewerName);
        ctx.setVariable("comment", comment);
        ctx.setVariable("documentLink", documentLink);

        sendTemplated("approval-decision", to, "Document '" + documentTitle + "' was " + decision, ctx);
    }

    /** Notify about a lifecycle status change. */
    @Async
    public void sendLifecycleChangeEmail(String to, String documentTitle, String newStatus,
                                          String changedBy, String documentLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("newStatus", newStatus);
        ctx.setVariable("changedBy", changedBy);
        ctx.setVariable("documentLink", documentLink);

        sendTemplated("lifecycle-change", to, "Document status changed: '" + documentTitle + "' is now " + newStatus, ctx);
    }

    /** Send org invitation email to an unregistered user. */
    @Async
    public void sendOrgInviteEmail(String to, String orgName, String inviterName,
                                    String role, String inviteLink) {
        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("inviterName", inviterName);
        ctx.setVariable("role", role);
        ctx.setVariable("inviteLink", inviteLink);

        sendTemplated("org-invite", to, "You've been invited to join " + orgName + " on Crixaa", ctx);
    }

    /**
     * Notify someone that a template was shared with them.
     * Simple pointer email — the recipient's actual access is governed by their
     * org membership; the share itself is just a "heads up" signal.
     */
    @Async
    public void sendTemplateSharedEmail(String to, String templateName,
                                         String sharerName, String templateUrl) {
        Context ctx = new Context();
        ctx.setVariable("templateName", templateName);
        ctx.setVariable("sharerName", sharerName);
        ctx.setVariable("templateUrl", templateUrl);

        sendTemplated("template-shared", to, sharerName + " shared \"" + templateName + "\" with you", ctx);
    }

    /** Ask a reviewer to look at a committed template version. */
    @Async
    public void sendReviewRequestedEmail(String to, String templateName, String requesterName,
                                          int versionNumber, String message, String reviewUrl) {
        Context ctx = new Context();
        ctx.setVariable("templateName", templateName);
        ctx.setVariable("requesterName", requesterName);
        ctx.setVariable("versionNumber", versionNumber);
        ctx.setVariable("message", message == null ? "" : message);
        ctx.setVariable("reviewUrl", reviewUrl);

        sendTemplated("review-requested", to, "Review requested: \"" + templateName + "\" (v" + versionNumber + ")", ctx);
    }

    /**
     * Tell the requester that the reviewer has approved or asked for changes.
     * {@code status} is the raw enum name ({@code APPROVED} or {@code CHANGES_REQUESTED}).
     */
    @Async
    public void sendReviewDecisionEmail(String to, String templateName, String reviewerName,
                                         String status, String summary, String templateUrl) {
        Context ctx = new Context();
        ctx.setVariable("templateName", templateName);
        ctx.setVariable("reviewerName", reviewerName);
        ctx.setVariable("status", status);
        ctx.setVariable("approved", "APPROVED".equals(status));
        ctx.setVariable("summary", summary == null ? "" : summary);
        ctx.setVariable("templateUrl", templateUrl);

        String verb = "APPROVED".equals(status) ? "approved" : "requested changes on";
        sendTemplated("review-decision", to,
                reviewerName + " " + verb + " \"" + templateName + "\"", ctx);
    }

    /** Warn a workspace admin that an API key is about to expire. */
    @Async
    public void sendApiKeyExpiryWarningEmail(String to, String orgName, String keyName,
                                              long daysLeft, String developerUrl) {
        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("keyName", keyName);
        ctx.setVariable("daysLeft", daysLeft);
        ctx.setVariable("developerUrl", developerUrl);

        sendTemplated("api-key-expiry-warning", to, "API key \"" + keyName + "\" expires in " + daysLeft + " day"
                + (daysLeft == 1 ? "" : "s"), ctx);
    }

    /** Warn about an upcoming or past document expiration. */
    @Async
    public void sendExpirationWarningEmail(String to, String documentTitle,
                                            String expiresAt, String documentLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("expiresAt", expiresAt);
        ctx.setVariable("documentLink", documentLink);

        sendTemplated("expiration-warning", to, "Document expiring: '" + documentTitle + "'", ctx);
    }

    // ── Internal ──

    private void send(String to, String subject, String htmlBody) {
        if (!resendProps.isConfigured()) {
            log.warn("Resend not configured (set RESEND_API_KEY) — skipping email to={} subject=\"{}\"",
                    to, subject);
            return;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("from", buildFrom());
            // Resend accepts a string or an array for "to"; we always send one
            // recipient, but the array form keeps the payload valid either way.
            body.putArray("to").add(to);
            body.put("subject", subject);
            body.put("html", htmlBody);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resendProps.getBaseUrl() + "/emails"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + resendProps.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                // Resend returns a JSON error body ({name, message, statusCode})
                // — log it verbatim so a bad "from" domain or key is obvious.
                log.error("Resend returned {} for to={} subject=\"{}\": {}",
                        resp.statusCode(), to, subject, resp.body());
                return;
            }
            log.info("Email sent via Resend to={} subject=\"{}\"", to, subject);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted sending email to={} subject=\"{}\"", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to={} subject=\"{}\": {}", to, subject, e.getMessage());
        }
    }

    /**
     * Resend's "from" field takes either a bare address or a display-name form
     * ("Name &lt;addr&gt;"). Note the address's domain must be a domain you've
     * verified in Resend (or resend.dev for testing) or the API rejects it.
     */
    private String buildFrom() {
        String name = emailProps.getFromName();
        String addr = emailProps.getFromAddress();
        if (name == null || name.isBlank()) {
            return addr;
        }
        return name + " <" + addr + ">";
    }

    /**
     * Render a template and send it, preferring a staff-authored override.
     *
     * <p>Overrides live in {@code admin_email_templates} and were, until now,
     * written by the admin API and read by nothing — the claim that "an
     * override row wins at send time" was simply untrue. This is where it
     * becomes true.
     *
     * <p>An override supplies both subject and body, and both are rendered
     * through Thymeleaf so they keep the same variables as the bundled
     * template. Looked up per send rather than cached: staff edit these
     * expecting the next email to reflect the change, and it is one indexed
     * primary-key read on an already-async path.
     *
     * <p>A broken override falls back to the bundled template rather than
     * dropping the email — a malformed edit should not stop a password reset.
     */
    private void sendTemplated(String key, String to, String defaultSubject, Context ctx) {
        AdminEmailTemplate override = null;
        try {
            override = overrideRepo.findById(key).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read email override for {}: {}", key, e.getMessage());
        }

        if (override != null) {
            try {
                // A blank override subject means "keep the built-in one", and the
                // built-in is NOT re-rendered — it is already a finished string
                // computed at the call site, often carrying live values (the OTP
                // code, the document title). Pushing it back through a template
                // engine would at best be a no-op and at worst mangle it.
                String raw = override.getSubject();
                String subject = (raw == null || raw.isBlank())
                        ? defaultSubject
                        : subjectTemplateEngine.process(raw, ctx);
                String body = stringTemplateEngine.process(override.getBodyHtml(), ctx);
                send(to, subject, body);
                return;
            } catch (Exception e) {
                // Exception, not RuntimeException: a missing expression-language
                // artifact surfaces as a LinkageError-adjacent failure that a
                // RuntimeException catch walks straight past, and the email is
                // then dropped rather than falling back — the opposite of what
                // the contract above promises.
                log.error("Email override for {} failed to render — falling back to the bundled template",
                        key, e);
            }
        }

        send(to, defaultSubject, templateEngine.process("email/" + key, ctx));
    }

}
