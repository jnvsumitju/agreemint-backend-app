package com.agreemint.api;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.PreviewPdfRequest;
import com.agreemint.service.DocumentGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "PDF Generation", description = "Generate and preview PDF documents")
@RestController
@RequestMapping("/api")
public class GenerateController {

    private final DocumentGenerationService documentGenerationService;

    public GenerateController(DocumentGenerationService documentGenerationService) {
        this.documentGenerationService = documentGenerationService;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        return documentGenerationService.generate(request);
    }

    @PostMapping(
            value = "/generate/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public byte[] previewPdf(@RequestBody PreviewPdfRequest request) {
        return documentGenerationService.renderPreviewPdf(request.layout(), request.data());
    }
}
