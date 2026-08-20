package com.agreemint.service;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.billing.PdfQuotaService;
import com.agreemint.billing.PlanGate;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateStatus;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.pdf.PdfRendererService;
import com.agreemint.pdf.PdfSigningService;
import com.agreemint.pdf.VerificationMark;
import com.agreemint.repository.DocumentReceiptRepository;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the generate path actually applies the flat-to-nested conversion.
 *
 * <p>{@code ApiDataShapeTest} pins the conversion itself, and it passes with
 * this wiring removed — it never asks whether anyone calls it. That is exactly
 * the bug: {@code VariableDataTree} existed, was correct, and was used by the
 * console and the thumbnail renderer while the public API alone passed the
 * caller's JSON straight to iText.
 *
 * <p>So this asserts on the argument the renderer receives, which is the only
 * question that matters to someone whose PDF came back blank.
 */
class GenerateDataWiringTest {

    private static final ObjectMapper M = new ObjectMapper();

    private PdfRendererService renderer;
    private DocumentGenerationService service;

    @BeforeEach
    void setUp() throws Exception {
        renderer = mock(PdfRendererService.class);
        var versions = mock(TemplateVersionService.class);
        var docs = mock(GeneratedDocumentRepository.class);
        var receipts = mock(DocumentReceiptRepository.class);
        var signing = mock(PdfSigningService.class);
        var r2 = mock(R2StorageService.class);
        var webhooks = mock(WebhookService.class);
        var planGate = mock(PlanGate.class);
        var quota = mock(PdfQuotaService.class);

        Template tpl = new Template();
        tpl.setId(UUID.randomUUID());
        tpl.setOrgId(UUID.randomUUID());
        tpl.setStatus(TemplateStatus.ACTIVE);

        TemplateVersion version = new TemplateVersion();
        version.setId(UUID.randomUUID());
        version.setTemplate(tpl);
        version.setLayoutJson(M.readTree("{\"page\":{\"size\":\"A4\"},\"elements\":[]}"));

        when(versions.getVersionEntity(any(), any())).thenReturn(version);
        // The generate path calls renderWithWarnings, not render — it needs the
        // unresolved-placeholder list for the response as well as the bytes.
        when(renderer.renderWithWarnings(any(), any(), anyBoolean(), any(VerificationMark.class)))
                .thenReturn(new PdfRendererService.RenderResult(
                        "%PDF-1.7".getBytes(), java.util.List.of()));
        when(signing.sign(any())).thenAnswer(inv -> inv.getArgument(0));
        when(docs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DocumentGenerationService(
                versions, docs, receipts, renderer, signing, r2, webhooks, planGate, quota);
    }

    private JsonNode dataReachingTheRenderer(String payload) throws Exception {
        GenerateRequest req = new GenerateRequest(
                UUID.randomUUID(), UUID.randomUUID(), M.readTree(payload));
        try {
            service.generate(req, UUID.randomUUID(), UUID.randomUUID());
        } catch (RuntimeException expected) {
            // Storage/webhook mocks are inert, so the call may not run to
            // completion. The render has already happened by then, which is the
            // only step under test.
        }
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(renderer).renderWithWarnings(
                any(), captor.capture(), anyBoolean(), any(VerificationMark.class));
        return captor.getValue();
    }

    private static JsonNode walk(JsonNode root, String path) {
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || !cur.has(part)) return null;
            cur = cur.get(part);
        }
        return cur;
    }

    @Test
    void dottedKeysFromTheApiReachTheRendererAsANestedTree() throws Exception {
        JsonNode seen = dataReachingTheRenderer("{\"company.name\":\"Acme Ltd\"}");

        assertNotNull(walk(seen, "company.name"),
                "the renderer still cannot resolve company.name — the conversion is not wired in");
        assertEquals("Acme Ltd", walk(seen, "company.name").asText());
    }

    @Test
    void nestedPayloadsStillArriveIntact() throws Exception {
        JsonNode seen = dataReachingTheRenderer("{\"customer\":\"Beta\",\"amount\":2400}");

        assertEquals("Beta", walk(seen, "customer").asText());
        assertEquals(2400, walk(seen, "amount").asInt());
    }
}
