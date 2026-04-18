package com.agreemint.api;

import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.service.DocumentGenerationService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

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
     * Authenticated read of a generated PDF. The bytes are streamed from R2
     * through the backend rather than 302-redirected to a presigned URL —
     * that way the browser sees a same-origin response and no cross-origin
     * preflight hits R2. Slightly more backend egress than a redirect, but
     * avoids needing a CORS policy on the bucket.
     *
     * <p>Spring closes the {@link InputStreamResource} when the response
     * body finishes writing, which also closes the underlying S3 stream.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> file(@PathVariable UUID id) {
        ResponseInputStream<GetObjectResponse> s3Stream =
                documentGenerationService.openDocumentStream(id);
        GetObjectResponse meta = s3Stream.response();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(meta.contentLength() == null ? -1L : meta.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + id + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(new InputStreamResource(s3Stream));
    }
}
