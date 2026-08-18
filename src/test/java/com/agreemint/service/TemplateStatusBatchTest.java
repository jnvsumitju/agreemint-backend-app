package com.agreemint.service;

import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Template;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateDraftRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Version and draft state for the templates list.
 *
 * <p>The list is the first screen after login and already fans out for product
 * names, so the interesting property is not just that the numbers are right but
 * that they cost two queries for the whole page rather than two per template.
 * An N+1 here would not fail anything — it would just make the list slower for
 * exactly the customers with the most templates.
 */
class TemplateStatusBatchTest {

    private TemplateRepository templateRepo;
    private TemplateVersionRepository versionRepo;
    private TemplateDraftRepository draftRepo;
    private TemplateService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID committed = UUID.randomUUID();
    private final UUID edited = UUID.randomUUID();
    private final UUID neverCommitted = UUID.randomUUID();

    private Template template(UUID id, String name) {
        Template t = new Template();
        t.setId(id);
        t.setName(name);
        t.setOrgId(orgId);
        return t;
    }

    @BeforeEach
    void setUp() {
        templateRepo = mock(TemplateRepository.class);
        versionRepo = mock(TemplateVersionRepository.class);
        draftRepo = mock(TemplateDraftRepository.class);
        service = new TemplateService(
                templateRepo,
                mock(ProductService.class),
                mock(ProductRepository.class),
                mock(TemplateVersionService.class),
                versionRepo,
                draftRepo);

        when(templateRepo.findByOrgIdOrderByCreatedAtDesc(orgId)).thenReturn(List.of(
                template(committed, "Committed"),
                template(edited, "Edited since commit"),
                template(neverCommitted, "Never committed")));

        when(versionRepo.findMaxVersionByTemplateIds(anyCollection())).thenReturn(List.of(
                new Object[] { committed, 3 },
                new Object[] { edited, 2 }));
        // Committing deletes the draft row, so a row here means uncommitted work.
        when(draftRepo.findTemplateIdsWithDraft(anyCollection())).thenReturn(List.of(edited));
    }

    @Test
    void reportsEachTemplatesRealState() {
        List<TemplateResponse> rows = service.listForOrg(orgId, null);
        assertEquals(3, rows.size());

        TemplateResponse a = rows.stream().filter(r -> r.id().equals(committed)).findFirst().orElseThrow();
        assertEquals(3, a.versionNumber());
        assertFalse(a.hasUncommittedChanges());

        TemplateResponse b = rows.stream().filter(r -> r.id().equals(edited)).findFirst().orElseThrow();
        assertEquals(2, b.versionNumber());
        assertTrue(b.hasUncommittedChanges(), "a draft row means edits that are in no version");

        TemplateResponse c = rows.stream().filter(r -> r.id().equals(neverCommitted)).findFirst().orElseThrow();
        assertNull(c.versionNumber(), "never committed must be null, not 0 — 0 would read as a version");
        assertFalse(c.hasUncommittedChanges());
    }

    @Test
    void costsTwoQueriesForTheWholePageNotTwoPerTemplate() {
        service.listForOrg(orgId, null);
        verify(versionRepo, times(1)).findMaxVersionByTemplateIds(anyCollection());
        verify(draftRepo, times(1)).findTemplateIdsWithDraft(anyCollection());
        // And specifically not the per-row lookups that would be the easy way
        // to write this.
        verify(versionRepo, times(0)).findFirstByTemplateOrderByVersionNumberDesc(any());
    }

    @Test
    void anEmptyWorkspaceQueriesNothing() {
        UUID empty = UUID.randomUUID();
        when(templateRepo.findByOrgIdOrderByCreatedAtDesc(empty)).thenReturn(List.of());

        assertEquals(List.of(), service.listForOrg(empty, null));
        // `IN ()` is a syntax error in some dialects and a full scan in others;
        // either way there is nothing to ask about.
        verify(versionRepo, times(0)).findMaxVersionByTemplateIds(anyCollection());
        verify(draftRepo, times(0)).findTemplateIdsWithDraft(anyCollection());
    }
}
