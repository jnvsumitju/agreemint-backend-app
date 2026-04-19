package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage / billing dashboard summary. V2 scope — the daily bucket + top-org
 * breakdowns are currently stubbed with empty arrays; aggregates land when
 * we're sure of the event schema we're billing on.
 */
@Tag(name = "Admin · Usage")
@RestController
@RequestMapping("/api/admin/usage")
public class AdminUsageController {

    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final TemplateRepository templateRepo;
    private final GeneratedDocumentRepository docRepo;

    public AdminUsageController(
            OrganizationRepository orgRepo,
            UserRepository userRepo,
            TemplateRepository templateRepo,
            GeneratedDocumentRepository docRepo) {
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.templateRepo = templateRepo;
        this.docRepo = docRepo;
    }

    @GetMapping
    public AdminDtos.UsageSummary summary() {
        long totalOrgs = orgRepo.count();
        long totalUsers = userRepo.count();
        long totalTemplates = templateRepo.count();
        long docsAll = docRepo.count();

        // 30-day zero-filled histogram. Real aggregation is a native
        // `date_trunc('day', created_at) group by 1` query — saving that
        // for when the billing story firms up.
        List<AdminDtos.UsageBucket> daily = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = 29; i >= 0; i--) {
            daily.add(new AdminDtos.UsageBucket(today.minusDays(i).toString(), 0L));
        }

        return new AdminDtos.UsageSummary(
                totalOrgs,
                totalUsers,
                totalTemplates,
                docsAll,
                0L, // apiCallsLast30d: needs api_key_usage table; stubbed
                daily,
                List.of());
    }
}
