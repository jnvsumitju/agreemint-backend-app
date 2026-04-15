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
