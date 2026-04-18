package com.agreemint.api;

import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.service.DocumentGenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Documents", description = "Generated PDF document access")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentGenerationService documentGenerationService;

    public DocumentController(DocumentGenerationService documentGenerationService) {
        this.documentGenerationService = documentGenerationService;
    }

    @GetMapping("/{id}")
    public GeneratedDocumentResponse get(@PathVariable UUID id) {
        return documentGenerationService.getDocument(id);
    }

    /**
     * Authenticated read of a generated PDF. Returns a 302 redirect to a
     * short-TTL presigned R2 URL so the bytes never flow through the JVM.
     * Browsers strip the Authorization header on cross-origin redirect,
     * which is fine — the R2 URL carries its own signature in the query
     * string.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Void> file(@PathVariable UUID id) {
        URL presigned = documentGenerationService.resolvePresignedUrl(id);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, presigned.toString())
                // Disable intermediate caching — signed URLs are scoped to a
                // single requester and expire in minutes.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
