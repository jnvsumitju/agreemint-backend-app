package com.agreemint.admin.api;

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
 * Override bank for system email templates. An override row wins at send
 * time; with no row the baked-in Thymeleaf template is used. Subject + body
 * are treated as Thymeleaf strings with {@code {{var}}} substitutions.
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
            @RequestBody AdminDtos.EmailTemplateUpsertRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        AdminEmailTemplate t = repo.findById(key).orElseGet(() -> {
            AdminEmailTemplate n = new AdminEmailTemplate();
            n.setKey(key);
            return n;
        });
        t.setSubject(req.subject());
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
}
