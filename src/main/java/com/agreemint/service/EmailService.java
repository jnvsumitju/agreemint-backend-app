package com.agreemint.service;

import com.agreemint.config.EmailProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sends transactional emails using Thymeleaf templates.
 * All methods are @Async so they don't block the calling thread.
 * If mail is not configured (e.g. dev), failures are logged but not thrown.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final EmailProperties emailProps;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine, EmailProperties emailProps) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.emailProps = emailProps;
    }

    /** Send password reset email with a clickable link. */
    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        Context ctx = new Context();
        ctx.setVariable("resetLink", resetLink);
        ctx.setVariable("expiryMinutes", 60);

        String html = templateEngine.process("email/password-reset", ctx);
        send(to, "Reset your Agreemint password", html);
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
        send(to, "Your Agreemint login code: " + otpCode, html);
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
        send(to, "You've been invited to join " + orgName + " on Agreemint", html);
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailProps.getFromAddress(), emailProps.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to={} subject=\"{}\"", to, subject);
        } catch (MailException | MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email to={} subject=\"{}\": {}", to, subject, e.getMessage());
        }
    }
}
