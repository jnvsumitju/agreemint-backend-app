package com.agreemint.service;

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
    private final EmailProperties emailProps;
    private final ResendProperties resendProps;
    private final HttpClient http;

    public EmailService(TemplateEngine templateEngine, EmailProperties emailProps, ResendProperties resendProps) {
        this.templateEngine = templateEngine;
        this.emailProps = emailProps;
        this.resendProps = resendProps;
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

        String html = templateEngine.process("email/password-reset", ctx);
        send(to, "Reset your Crixaa password", html);
    }

    /** Send email verification link after registration. */
    @Async
    public void sendEmailVerificationEmail(String to, String verifyLink) {
        Context ctx = new Context();
        ctx.setVariable("verifyLink", verifyLink);

        String html = templateEngine.process("email/email-verification", ctx);
        send(to, "Verify your email address", html);
    }

    /** Send OTP code for passwordless login. */
    @Async
    public void sendOtpEmail(String to, String otpCode, int ttlMinutes) {
        Context ctx = new Context();
        ctx.setVariable("otpCode", otpCode);
        ctx.setVariable("ttlMinutes", ttlMinutes);

        String html = templateEngine.process("email/otp-code", ctx);
        send(to, "Your Crixaa login code: " + otpCode, html);
    }

    /** Notify an approver they have a pending approval step. */
    @Async
    public void sendApprovalRequestEmail(String to, String documentTitle,
                                          String approverName, String reviewLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("approverName", approverName);
        ctx.setVariable("reviewLink", reviewLink);

        String html = templateEngine.process("email/approval-request", ctx);
        send(to, "Action required: Review '" + documentTitle + "'", html);
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

        String html = templateEngine.process("email/approval-decision", ctx);
        send(to, "Document '" + documentTitle + "' was " + decision, html);
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

        String html = templateEngine.process("email/lifecycle-change", ctx);
        send(to, "Document status changed: '" + documentTitle + "' is now " + newStatus, html);
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

        String html = templateEngine.process("email/org-invite", ctx);
        send(to, "You've been invited to join " + orgName + " on Crixaa", html);
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

        String html = templateEngine.process("email/template-shared", ctx);
        send(to, sharerName + " shared \"" + templateName + "\" with you", html);
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

        String html = templateEngine.process("email/review-requested", ctx);
        send(to, "Review requested: \"" + templateName + "\" (v" + versionNumber + ")", html);
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

        String html = templateEngine.process("email/review-decision", ctx);
        String verb = "APPROVED".equals(status) ? "approved" : "requested changes on";
        send(to, reviewerName + " " + verb + " \"" + templateName + "\"", html);
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

        String html = templateEngine.process("email/api-key-expiry-warning", ctx);
        send(to, "API key \"" + keyName + "\" expires in " + daysLeft + " day"
                + (daysLeft == 1 ? "" : "s"), html);
    }

    /** Warn about an upcoming or past document expiration. */
    @Async
    public void sendExpirationWarningEmail(String to, String documentTitle,
                                            String expiresAt, String documentLink) {
        Context ctx = new Context();
        ctx.setVariable("documentTitle", documentTitle);
        ctx.setVariable("expiresAt", expiresAt);
        ctx.setVariable("documentLink", documentLink);

        String html = templateEngine.process("email/expiration-warning", ctx);
        send(to, "Document expiring: '" + documentTitle + "'", html);
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
}
