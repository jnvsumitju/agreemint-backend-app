package com.agreemint.admin.api;

import java.util.Set;
import com.agreemint.service.EmailTemplateCatalog;
import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.AdminEmailTemplate;
import com.agreemint.admin.repository.AdminEmailTemplateRepository;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Override bank for system email templates. An override row wins at send time;
 * with no row the baked-in Thymeleaf template is used.
 *
 * <p><strong>Subject and body are Thymeleaf</strong>, rendered through
 * {@code stringTemplateEngine} — so a variable is {@code [[${name}]]} in text
 * or {@code th:text="${name}"} on an element. An earlier version of this
 * Javadoc claimed {@code {{var}}}, which is Mustache syntax and would have been
 * emitted to customers verbatim.
 *
 * <p>Which variables exist per key is answered by {@link #catalog()}. Thymeleaf
 * renders an unknown expression as empty rather than raising, so a wrong name
 * produces a blank spot in an email that still sends — the catalogue is what
 * keeps staff from guessing.
 */
@Tag(name = "Admin · Email Templates")
@RestController
@RequestMapping("/api/admin/email-templates")
public class AdminEmailTemplateController {

    private final AdminEmailTemplateRepository repo;

    public AdminEmailTemplateController(AdminEmailTemplateRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<AdminDtos.EmailTemplateResponse> list() {
        return repo.findAll().stream()
                .map(t -> new AdminDtos.EmailTemplateResponse(
                        t.getKey(), t.getSubject(), t.getBodyHtml(), t.getUpdatedAt()))
                .toList();
    }

    @PutMapping("/{key}")
    public AdminDtos.EmailTemplateResponse upsert(
            @PathVariable String key,
            @jakarta.validation.Valid @RequestBody AdminDtos.EmailTemplateUpsertRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        // EmailService only ever looks up the keys it sends, so a row under an
        // unrecognised key is inert — it saves cleanly, shows up in the list,
        // and never reaches a customer. Rejecting the typo is the only way the
        // author finds out.
        if (!EmailTemplateCatalog.isKnown(key)) {
            throw new com.agreemint.api.BadRequestException(
                    "Unknown template key '" + key + "'. See GET /api/admin/email-templates/catalog.");
        }
        AdminEmailTemplate t = repo.findById(key).orElseGet(() -> {
            AdminEmailTemplate n = new AdminEmailTemplate();
            n.setKey(key);
            return n;
        });
        // Coalesced, never null: the column is NOT NULL, and subject stopped
        // being @NotBlank so that omitting it can mean "keep the built-in
        // subject". Blank is how that intent is stored.
        t.setSubject(req.subject() == null ? "" : req.subject().trim());
        t.setBodyHtml(req.bodyHtml());
        t.setUpdatedBy(principal.userId());
        t.setUpdatedAt(Instant.now());
        repo.save(t);
        return new AdminDtos.EmailTemplateResponse(
                t.getKey(), t.getSubject(), t.getBodyHtml(), t.getUpdatedAt());
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        repo.deleteById(key);
        return ResponseEntity.noContent().build();
    }

    /**
     * Every template the product can send, with its default subject and whether
     * an override currently exists.
     *
     * <p>The override table only holds rows that have been created, so it
     * cannot answer "what can I override?" — without this the portal would
     * have to hardcode the list and drift from the backend.
     */
    @GetMapping("/catalog")
    public List<CatalogEntry> catalog() {
        Set<String> overridden = repo.findAll().stream()
                .map(AdminEmailTemplate::getKey)
                .collect(java.util.stream.Collectors.toSet());

        return EmailTemplateCatalog.ALL.stream()
                .map(e -> new CatalogEntry(e.key(), e.description(), e.defaultSubject(),
                        defaultBody(e.key()), e.variables(), overridden.contains(e.key())))
                .toList();
    }

    /**
     * The bundled template source for a key, or "" if there is no file.
     *
     * <p>Without this the editor opened on an empty box: nothing in the API
     * could supply the shipped body, so an operator wanting to change one line
     * of a 3 KB email had to author the whole thing from nothing. The migration
     * comment claimed the table was "seeded from files on first deploy", which
     * no code does — that claim is what made the gap easy to miss.
     *
     * <p>These are static classpath files with no secrets in them, and the whole
     * controller is ROLE_STAFF-only.
     */
    private String defaultBody(String key) {
        var res = new org.springframework.core.io.ClassPathResource("templates/email/" + key + ".html");
        try (var in = res.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /**
     * @param variables the names available in this template's Thymeleaf context
     */
    public record CatalogEntry(String key, String description, String defaultSubject,
                                String defaultBodyHtml,
                               List<String> variables, boolean overridden) {}

}
