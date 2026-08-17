package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.DocumentLifecycleResponse;
import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.DocumentLifecycleEvent;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.LifecycleStatus;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.domain.User;
import com.agreemint.repository.ApprovalStepRepository;
import com.agreemint.repository.ApprovalWorkflowRepository;
import com.agreemint.repository.DocumentLifecycleEventRepository;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cover for document expiry.
 *
 * <p>The auto-expire job, the EXPIRED status, the timeline label and the email
 * template all shipped a long time ago — but nothing ever wrote
 * {@code expires_at}, so the sweep queried an all-NULL column and the advertised
 * behaviour had never run once. These tests pin the write path that makes the
 * rest reachable, and the send-once state that stops the warning email
 * repeating on every sweep and every instance.
 */
class DocumentExpiryTest {

    private GeneratedDocumentRepository documentRepo;
    private DocumentLifecycleEventRepository eventRepo;
    private UserRepository userRepo;
    private NotificationService notificationService;
    private EmailService emailService;
    private WebhookService webhookService;
    private ActivityService activityService;
    private DocumentLifecycleService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final List<DocumentLifecycleEvent> events = new ArrayList<>();

    private GeneratedDocument doc;

    @BeforeEach
    void setUp() {
        documentRepo = mock(GeneratedDocumentRepository.class);
        eventRepo = mock(DocumentLifecycleEventRepository.class);
        userRepo = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        webhookService = mock(WebhookService.class);
        activityService = mock(ActivityService.class);
        events.clear();

        when(eventRepo.save(any())).thenAnswer((i) -> {
            events.add(i.getArgument(0));
            return i.getArgument(0);
        });

        User actor = new User();
        actor.setId(actorId);
        actor.setName("Ada");
        actor.setEmail("ada@example.test");
        when(userRepo.findById(actorId)).thenReturn(Optional.of(actor));

        User creator = new User();
        creator.setId(creatorId);
        creator.setEmail("creator@example.test");
        when(userRepo.findById(creatorId)).thenReturn(Optional.of(creator));

        Template template = new Template();
        template.setId(UUID.randomUUID());

        // The response builder dereferences the version, so a document without
        // one NPEs before it reaches anything this test is about.
        TemplateVersion version = new TemplateVersion();
        version.setId(UUID.randomUUID());
        version.setTemplate(template);
        version.setVersionNumber(1);

        doc = new GeneratedDocument();
        doc.setVersion(version);
        doc.setId(UUID.randomUUID());
        doc.setTitle("Q4 offer");
        doc.setOrgId(orgId);
        doc.setCreatedBy(creatorId);
        doc.setTemplate(template);
        doc.setSource(DocumentSource.UI_GENERATED);
        doc.setLifecycleStatus(LifecycleStatus.ACTIVE);
        when(documentRepo.findById(doc.getId())).thenReturn(Optional.of(doc));

        FrontendProperties frontend = new FrontendProperties();
        frontend.setBaseUrl("https://console.example.test");

        service = new DocumentLifecycleService(
                documentRepo, eventRepo,
                mock(com.agreemint.repository.DocumentReceiptRepository.class),
                mock(ApprovalWorkflowRepository.class), mock(ApprovalStepRepository.class),
                userRepo, mock(ProductRepository.class),
                notificationService, activityService, emailService, frontend, webhookService);
    }

    private Instant inDays(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    // ── setting the date ──────────────────────────────────────────────────

    @Test
    void settingAnExpiryActuallyWritesTheColumn() {
        // The whole feature was unreachable because nothing did this.
        Instant when = inDays(30);

        service.setExpiry(doc.getId(), when, actorId, orgId);

        assertEquals(when, doc.getExpiresAt());
        verify(documentRepo).save(doc);
    }

    @Test
    void clearingAnExpiryIsAllowed() {
        doc.setExpiresAt(inDays(5));

        service.setExpiry(doc.getId(), null, actorId, orgId);

        assertNull(doc.getExpiresAt(), "removing a date must be possible, not just changing it");
    }

    @Test
    void changingTheDateRearmsTheWarning() {
        // Otherwise a document warned about its old date is never warned about
        // the new one, and the customer is surprised by an expiry they moved.
        doc.setExpiresAt(inDays(2));
        doc.setExpiryWarnedAt(Instant.now());

        service.setExpiry(doc.getId(), inDays(60), actorId, orgId);

        assertNull(doc.getExpiryWarnedAt());
    }

    @Test
    void aDateInThePastIsRejected() {
        assertThrows(BadRequestException.class,
                () -> service.setExpiry(doc.getId(), Instant.now().minusSeconds(60), actorId, orgId));
        assertNull(doc.getExpiresAt());
    }

    @Test
    void apiGeneratedDocumentsCannotBeGivenAnExpiry() {
        // transitionStatus refuses lifecycle moves for these, so an expiry date
        // would be a promise nothing can keep.
        doc.setSource(DocumentSource.API_GENERATED);

        assertThrows(BadRequestException.class,
                () -> service.setExpiry(doc.getId(), inDays(10), actorId, orgId));
    }

    @Test
    void settingAnExpiryIsRecordedOnTheTimeline() {
        service.setExpiry(doc.getId(), inDays(14), actorId, orgId);

        assertEquals(1, events.size());
        assertEquals("EXPIRY_SET", events.get(0).getEventType());
        assertEquals("Ada", events.get(0).getActorName());
    }

    @Test
    void clearingAnExpiryIsDistinguishableOnTheTimeline() {
        doc.setExpiresAt(inDays(3));

        service.setExpiry(doc.getId(), null, actorId, orgId);

        assertEquals("EXPIRY_CLEARED", events.get(0).getEventType());
    }

    @Test
    void aDocumentInAnotherWorkspaceCannotBeTouched() {
        // Role and plan are checked against the CALLER's org, which only
        // establishes "you are an admin somewhere". Without a tenant check on
        // the document itself, an admin of any Pro workspace could expire a
        // different customer's documents by guessing an id.
        UUID someoneElsesOrg = UUID.randomUUID();

        assertThrows(NotFoundException.class,
                () -> service.setExpiry(doc.getId(), inDays(5), actorId, someoneElsesOrg));
        assertNull(doc.getExpiresAt());
    }

    @Test
    void aNullActingOrgIsRefused() {
        assertThrows(NotFoundException.class,
                () -> service.setExpiry(doc.getId(), inDays(5), actorId, null));
    }

    // ── warning ahead of the date ─────────────────────────────────────────

    private void dueSoon(GeneratedDocument... docs) {
        when(documentRepo
                .findByLifecycleStatusAndSourceNotAndExpiryWarnedAtIsNullAndExpiresAtBetweenOrderByExpiresAtAsc(
                        any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(docs));
    }

    @Test
    void aDocumentDueSoonGetsAWarningEmail() {
        doc.setExpiresAt(inDays(3));
        dueSoon(doc);

        int sent = service.sendExpiryWarnings(7, 500);

        assertEquals(1, sent);
        verify(emailService).sendDocumentExpiringSoonEmail(
                eq("creator@example.test"), eq("Q4 offer"), anyString(), anyString());
    }

    @Test
    void theWarningUsesTheDedicatedTemplateNotThePastTenseOne() {
        doc.setExpiresAt(inDays(3));
        dueSoon(doc);

        service.sendExpiryWarnings(7, 500);

        // sendExpirationWarningEmail renders "Document expired" — sending it to
        // someone whose document is still live would be simply wrong.
        verify(emailService, never()).sendExpirationWarningEmail(any(), any(), any(), any());
    }

    @Test
    void theWarningLinkIsAbsolute() {
        doc.setExpiresAt(inDays(3));
        dueSoon(doc);

        service.sendExpiryWarnings(7, 500);

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendDocumentExpiringSoonEmail(any(), any(), any(), link.capture());
        // A relative "/documents/{id}" is a dead link in a mail client.
        assertTrue(link.getValue().startsWith("https://console.example.test/documents/"),
                "got: " + link.getValue());
    }

    @Test
    void warningMarksTheDocumentSoItIsNotSentTwice() {
        doc.setExpiresAt(inDays(3));
        dueSoon(doc);

        service.sendExpiryWarnings(7, 500);

        assertNotNull(doc.getExpiryWarnedAt(),
                "without this the sweep re-emails every run, on every instance");
    }

    @Test
    void warningEmitsTheExpiringWebhook() {
        doc.setExpiresAt(inDays(3));
        dueSoon(doc);

        service.sendExpiryWarnings(7, 500);

        verify(webhookService).emit(eq(orgId), eq("document.expiring"), any());
    }

    @Test
    void nothingDueMeansNoEmailAndNoWebhook() {
        dueSoon();

        assertEquals(0, service.sendExpiryWarnings(7, 500));
        verifyNoInteractions(emailService);
        verifyNoInteractions(webhookService);
    }

    @Test
    void theSweepIsBounded() {
        // A first backfill of dates across a large workspace must not load the
        // whole table into one transaction.
        dueSoon();

        service.sendExpiryWarnings(7, 250);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepo)
                .findByLifecycleStatusAndSourceNotAndExpiryWarnedAtIsNullAndExpiresAtBetweenOrderByExpiresAtAsc(
                        eq(LifecycleStatus.ACTIVE), eq(DocumentSource.API_GENERATED),
                        any(), any(), page.capture());
        assertEquals(250, page.getValue().getPageSize());
    }
}
