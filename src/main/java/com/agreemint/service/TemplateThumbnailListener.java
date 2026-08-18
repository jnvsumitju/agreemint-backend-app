package com.agreemint.service;

import com.agreemint.config.ThumbnailExecutorConfig;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.TemplateVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Renders the preview image for a committed version, off the request thread.
 *
 * <p>Committing used to pay for this inline: an iText render, a 150-DPI
 * rasterise and up to two uploads to object storage, all inside the commit
 * transaction and therefore all while its pooled database connection was held
 * open. The author sat and waited for a picture.
 *
 * <p>{@code AFTER_COMMIT} rather than plain {@code @Async}, and the distinction
 * is the whole design. Firing during the transaction would race it: the version
 * row might not be visible to another connection yet, and the template row is
 * still being written. Firing after means the work is only ever done for a
 * commit that actually succeeded — a rolled-back commit publishes no event at
 * all, so a failed save can no longer leave a thumbnail of a version that does
 * not exist.
 *
 * <p>It also means the draft row is gone by the time this runs, which is why
 * the event carries ids and the layout is re-read from {@code template_versions}.
 *
 * <p><b>Not durable.</b> A restart between the commit and this running loses
 * that render, and the thumbnail stays whatever it was until the next commit or
 * until someone opens the editor and the sixty-second capture fires. That is a
 * deliberate trade rather than an oversight: this codebase's durable pattern is
 * a database outbox drained by an {@code @Scheduled} poller, and all seven
 * existing scheduled jobs share a single thread with the webhook dispatcher's
 * blocking HTTP sends. Putting PDF rendering on that thread would make webhook
 * delivery worse to protect an image that can always be regenerated.
 */
@Component
public class TemplateThumbnailListener {

    private static final Logger log = LoggerFactory.getLogger(TemplateThumbnailListener.class);

    private final TemplateVersionRepository versionRepo;
    private final TemplateThumbnailService thumbnailService;

    public TemplateThumbnailListener(TemplateVersionRepository versionRepo,
                                     TemplateThumbnailService thumbnailService) {
        this.versionRepo = versionRepo;
        this.thumbnailService = thumbnailService;
    }

    @Async(ThumbnailExecutorConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTemplateCommitted(TemplateCommittedEvent event) {
        try {
            Optional<TemplateVersion> version = versionRepo.findById(event.versionId());
            if (version.isEmpty()) {
                // Only reachable if the version was deleted between the commit
                // and this running. Nothing to draw, and nothing wrong.
                log.debug("[thumbnail] Version {} gone before capture", event.versionId());
                return;
            }
            TemplateVersion v = version.get();
            thumbnailService.captureCommitted(event.templateId(), v.getLayoutJson(), v.getVariables());
        } catch (Throwable t) {
            // Nothing above this catches: an exception on an @Async void method
            // goes to the executor's uncaught handler and disappears. Losing a
            // thumbnail is acceptable; losing the reason it was lost is not.
            log.warn("[thumbnail] Async capture failed for template {} version {}: {}",
                    event.templateId(), event.versionId(), t.toString());
        }
    }
}
