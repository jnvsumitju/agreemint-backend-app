package com.agreemint.service;

import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.domain.DocumentStatus;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.GeneratedDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tenant isolation for reading a generated document.
 *
 * <p>These exist because {@code GET /api/documents/{id}} and
 * {@code /{id}/file} took a bare {@code @PathVariable UUID} and resolved it
 * with a plain {@code findById} — no principal, no org check. Everything under
 * {@code /api/**} requires *a* valid session, so the effect was that any
 * signed-in user of any workspace could read another tenant's document metadata
 * and stream their PDF, given only the id.
 *
 * <p>And the id was not a secret. It is the document's own {@code fileUrl}, the
 * R2 object key, the {@code Content-Disposition} filename, and a field in the
 * {@code document.generated} webhook payload.
 *
 * <p>The API-key surface was already guarded ({@code assertSameOrg} in
 * {@code PublicApiController}), so this was an asymmetry between the two ways
 * in rather than a missing concept — which is exactly the kind of gap that
 * survives review, because the guarded path reads as proof the rule exists.
 *
 * <p>The guard now lives in the service beside the data access, so both
 * surfaces inherit it whatever their controller does.
 */
class DocumentTenantIsolationTest {

    private GeneratedDocumentRepository documentRepo;
    private R2StorageService r2;
    private DocumentGenerationService service;

    private final UUID ownerOrgId = UUID.randomUUID();
    private final UUID intruderOrgId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        documentRepo = mock(GeneratedDocumentRepository.class);
        r2 = mock(R2StorageService.class);
        // `doReturn` rather than `when(...).thenReturn(...)`: the service is
        // built reflectively below and touches its collaborators, which leaves
        // Mockito mid-stubbing if the `when` form is used around it.
        doReturn(Optional.of(completedDocument())).when(documentRepo).findById(documentId);
        service = newServiceWithMocks();
    }

    // ── metadata read ─────────────────────────────────────────────────────

    @Test
    void ownerCanReadItsOwnDocument() {
        GeneratedDocumentResponse res = service.getDocument(documentId, ownerOrgId);
        assertEquals(documentId, res.id());
    }

    @Test
    void anotherWorkspaceCannotReadTheDocument() {
        NotFoundException e = assertThrows(NotFoundException.class,
                () -> service.getDocument(documentId, intruderOrgId));
        // 404, not 403: a 403 would confirm the document exists, which is the
        // fact being protected.
        assertEquals("Document not found", e.getMessage());
    }

    @Test
    void aSessionWithNoOrgCannotReadTheDocument() {
        assertThrows(NotFoundException.class, () -> service.getDocument(documentId, null));
    }

    // ── file bytes ────────────────────────────────────────────────────────

    @Test
    void anotherWorkspaceCannotStreamTheFile() {
        assertThrows(NotFoundException.class,
                () -> service.openDocumentStream(documentId, intruderOrgId));
        // The point of the assertion: object storage is never even reached, so
        // the bytes cannot leak through a partially-written response.
        verify(r2, never()).openDocument(any());
    }

    @Test
    void anotherWorkspaceCannotPresignTheFile() {
        assertThrows(NotFoundException.class,
                () -> service.resolvePresignedUrl(documentId, intruderOrgId));
        verify(r2, never()).presignDocumentGet(any());
    }

    /**
     * A presigned URL outlives the request that minted it, so leaking one is
     * worse than leaking a single response — hence its own case rather than
     * folding it into the stream test.
     */
    @Test
    void ownerStillGetsAPresignedUrl() {
        service.resolvePresignedUrl(documentId, ownerOrgId);
        verify(r2).presignDocumentGet(any());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private GeneratedDocument completedDocument() {
        GeneratedDocument d = new GeneratedDocument();
        set(d, "id", documentId);
        set(d, "orgId", ownerOrgId);
        set(d, "status", DocumentStatus.COMPLETED);
        set(d, "fileUrl", "/api/documents/" + documentId + "/file");
        set(d, "createdAt", Instant.now());

        Template t = new Template();
        set(t, "id", UUID.randomUUID());
        set(d, "template", t);

        TemplateVersion v = new TemplateVersion();
        set(v, "id", UUID.randomUUID());
        set(d, "version", v);
        return d;
    }

    /**
     * Entities here are JPA-managed with generated ids and no test builder, so
     * fields are set reflectively rather than adding setters that production
     * code has no reason to want.
     */
    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + field + " on " + target.getClass());
    }

    /**
     * Builds the service with only the two collaborators these paths touch,
     * leaving the rest null. Constructed reflectively so the test does not have
     * to track an unrelated change to the constructor's arity.
     */
    private DocumentGenerationService newServiceWithMocks() throws Exception {
        var ctor = DocumentGenerationService.class.getDeclaredConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == GeneratedDocumentRepository.class) args[i] = documentRepo;
            else if (types[i] == R2StorageService.class) args[i] = r2;
            else args[i] = null;
        }
        ctor.setAccessible(true);
        return (DocumentGenerationService) ctor.newInstance(args);
    }
}
