package com.agreemint.api;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.MeasureRequest;
import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.api.dto.PreviewPdfRequest;
import com.agreemint.api.dto.TextReflowRequest;
import com.agreemint.api.dto.TextReflowResponse;
import com.agreemint.pdf.LayoutMeasurementService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.DocumentGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "PDF Generation", description = "Generate and preview PDF documents")
@RestController
@RequestMapping("/api")
public class GenerateController {

    private final DocumentGenerationService documentGenerationService;
    private final LayoutMeasurementService layoutMeasurementService;

    public GenerateController(DocumentGenerationService documentGenerationService,
                              LayoutMeasurementService layoutMeasurementService) {
        this.documentGenerationService = documentGenerationService;
        this.layoutMeasurementService = layoutMeasurementService;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody GenerateRequest request) {
        return documentGenerationService.generate(request,
                principal != null ? principal.userId() : null,
                principal != null ? principal.orgId() : null);
    }

    @PostMapping(
            value = "/generate/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public byte[] previewPdf(@RequestBody PreviewPdfRequest request,
                              @AuthenticationPrincipal UserPrincipal principal) {
        // Org context decides whether the preview carries the free-plan
        // watermark, so it matches the document that would be generated.
        return documentGenerationService.renderPreviewPdf(
                request.layout(), request.data(),
                principal == null ? null : principal.orgId());
    }

    /**
     * Pixel-parity measurement pass — returns the per-line geometry iText would
     * compute for each element in the given layout, without producing PDF bytes.
     * The canvas consumes this output to replay iText's line-breaks instead of
     * letting CSS flow decide, so canvas and PDF stay identical.
     */
    @PostMapping("/generate/measure")
    public MeasureResponse measure(@RequestBody MeasureRequest request) {
        return layoutMeasurementService.measure(request.layout(), request.data(), request.elementIds());
    }

    /**
     * Decide where a TEXT element's content should split into linked frames,
     * using iText so the editor preview matches the eventual PDF. The canvas
     * runs its own DOM-based reflow as an instant approximation on paste,
     * then calls this endpoint to overwrite with the authoritative split.
     */
    @PostMapping("/generate/measure/reflow")
    public TextReflowResponse reflowText(@RequestBody TextReflowRequest request) {
        return layoutMeasurementService.reflow(request.headElement(), request.pageSpec(), request.data());
    }
}
