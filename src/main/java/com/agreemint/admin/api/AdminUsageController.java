package com.agreemint.admin.api;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
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
        // This used to report docRepo.count() — the all-time total — under a
        // "last 30 days" label.
        // Anchored to the same UTC day boundary the daily series below uses.
        // A rolling now-minus-720h window included part of a 31st day that the
        // series omitted, so the buckets never summed to this number and a
        // staff member comparing the two saw a discrepancy with no explanation.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant since = today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant();
        long docsLast30d = docRepo.countTotalSince(since);

        // Real per-day counts, zero-filled so the series is continuous even on
        // days with no activity. Previously every bucket was hardcoded to 0.
        Map<String, Long> byDay = new HashMap<>();
        for (Object[] row : docRepo.countByDaySince(since)) {
            byDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<AdminDtos.UsageBucket> daily = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            daily.add(new AdminDtos.UsageBucket(day, byDay.getOrDefault(day, 0L)));
        }

        // Busiest orgs by document volume. Previously always an empty list.
        Map<UUID, String> orgNames = new HashMap<>();
        orgRepo.findAll().forEach(o -> orgNames.put(o.getId(), o.getName()));
        List<AdminDtos.OrgUsageRow> topOrgs = docRepo
                .topOrgsSince(since, org.springframework.data.domain.PageRequest.of(0, 10))
                .stream()
                .map(row -> new AdminDtos.OrgUsageRow(
                        (UUID) row[0],
                        orgNames.get((UUID) row[0]),
                        ((Number) row[1]).longValue()))
                .toList();

        return new AdminDtos.UsageSummary(
                totalOrgs,
                totalUsers,
                totalTemplates,
                docsLast30d,
                daily,
                topOrgs);
    }
}
