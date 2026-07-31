package com.agreemint.service;

import java.util.List;
import java.util.Optional;

/**
 * The transactional emails the product sends.
 *
 * <p>Exists so the admin portal can offer "which templates can I override?"
 * without hardcoding a list in the frontend — the override table only holds
 * rows that already exist, so it cannot answer that question by itself.
 *
 * <p>Each key maps to {@code templates/email/<key>.html} and to the primary key
 * of {@code admin_email_templates}. Adding a template means adding it here too,
 * or it will be invisible to staff.
 *
 * <p>{@code variables} lists what {@code EmailService} actually puts in the
 * Thymeleaf context for that key. Without it an override is written blind:
 * Thymeleaf renders an unknown expression as empty rather than raising, so a
 * misremembered name yields a silently blank email that still sends. Keep these
 * in step with the {@code ctx.setVariable} calls in {@code EmailService}.
 */
public final class EmailTemplateCatalog {

    private EmailTemplateCatalog() {}

    public record Entry(String key, String description, String defaultSubject,
                        List<String> variables) {}

    public static final List<Entry> ALL = List.of(
            new Entry("impersonation-notice",
                    "Tells a customer that staff signed in to their account",
                    "Crixaa support signed in to your account",
                    List.of("headline", "summary", "orgName", "occurredAt", "detailLabel", "detail")),
            new Entry("data-export-notice",
                    "Tells a customer that staff exported their data",
                    "Crixaa support exported data from your account",
                    List.of("headline", "summary", "orgName", "occurredAt", "detailLabel", "detail")),
            new Entry("email-verification", "Sent after registration to confirm the address",
                    "Verify your email address",
                    List.of("verifyLink")),
            new Entry("password-reset", "Password reset link",
                    "Reset your Crixaa password",
                    List.of("resetLink", "expiryMinutes")),
            new Entry("otp-code", "One-time login code",
                    "Your Crixaa login code",
                    List.of("otpCode", "ttlMinutes")),
            new Entry("org-invite", "Invitation to join a workspace",
                    "You've been invited to join a workspace on Crixaa",
                    List.of("orgName", "inviterName", "role", "inviteLink")),
            new Entry("template-shared", "Someone shared a template with you",
                    "A template was shared with you",
                    List.of("sharerName", "templateName", "templateUrl")),
            new Entry("review-requested", "A reviewer is asked to look at a version",
                    "Review requested",
                    List.of("requesterName", "templateName", "versionNumber", "message", "reviewUrl")),
            new Entry("review-decision", "The reviewer approved or requested changes",
                    "Your review has a decision",
                    List.of("reviewerName", "templateName", "status", "approved", "summary", "templateUrl")),
            new Entry("approval-request", "An approver has a pending step",
                    "Action required: review a document",
                    List.of("approverName", "documentTitle", "reviewLink")),
            new Entry("approval-decision", "A document was approved or rejected",
                    "Your document has a decision",
                    List.of("documentTitle", "decision", "reviewerName", "comment", "documentLink")),
            new Entry("lifecycle-change", "A document changed lifecycle status",
                    "Document status changed",
                    List.of("documentTitle", "newStatus", "changedBy", "documentLink")),
            new Entry("expiration-warning", "A document is close to expiring",
                    "Document expiring",
                    List.of("documentTitle", "expiresAt", "documentLink")),
            new Entry("api-key-expiry-warning", "An API key is close to expiring",
                    "An API key is expiring soon",
                    List.of("keyName", "orgName", "daysLeft", "developerUrl"))
    );

    public static boolean isKnown(String key) {
        return ALL.stream().anyMatch(e -> e.key().equals(key));
    }

    public static Optional<Entry> find(String key) {
        return ALL.stream().filter(e -> e.key().equals(key)).findFirst();
    }
}
