package com.agreemint.billing;

import com.agreemint.config.RateLimitConfig;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Enforces the per-org daily document cap.
 *
 * <p>This exists because {@code org_quotas.pdf_daily_cap} was previously
 * written by the admin API and resolved by {@link OrgEntitlementService} but
 * read by nothing — setting it had no effect on the product at all. The admin
 * portal is a support tool, so a limit it displays has to be a limit that
 * holds.
 *
 * <p>Deliberate choices, all of which the admin UI states explicitly:
 * <ul>
 *   <li><b>No cap means no cap.</b> A null resolves to unlimited rather than to
 *       some system default. There is no system-wide PDF default to fall back
 *       to, and inventing one here would retroactively throttle every existing
 *       customer the moment this deployed.</li>
 *   <li><b>Zero means zero.</b> A cap of 0 blocks outright. Bucket4j cannot
 *       express an empty bucket — capacity is clamped to at least 1 — so this is
 *       short-circuited before any bucket is involved. Without that, a staff
 *       member setting 0 to stop an abusive workspace would still be handing it
 *       one document a day.</li>
 *   <li><b>Only persisted documents count.</b> Editor previews render PDFs too,
 *       but charging them against a document allowance would make the editor
 *       stop working mid-session, which is not what "documents per day" means
 *       to the person who set the number.</li>
 *   <li><b>A failed generation is refunded.</b> The charge happens before the
 *       work so a rejected request writes nothing, which means a render or
 *       upload failure has to give the token back — otherwise an R2 outage
 *       quietly burns a customer's whole daily allowance and leaves them with
 *       no documents to show for it.</li>
 *   <li><b>A rolling 24 hours, not a calendar day.</b> Bucket4j refills
 *       greedily, so allowance returns continuously rather than resetting at
 *       midnight — the same behaviour as the per-org API cap next to it.</li>
 * </ul>
 *
 * <p>Fails <em>open</em> if Redis is unreachable. A document cap is a
 * commercial limit, not a security boundary, and refusing to generate every
 * customer's documents during a Redis blip is far worse than briefly letting a
 * capped org over its number.
 */
@Service
public class PdfQuotaService {

    private static final Logger log = LoggerFactory.getLogger(PdfQuotaService.class);

    private final OrgEntitlementService entitlements;
    private final ProxyManager<String> proxyManager;

    public PdfQuotaService(OrgEntitlementService entitlements, ProxyManager<String> proxyManager) {
        this.entitlements = entitlements;
        this.proxyManager = proxyManager;
    }

    /**
     * Bucket key. Distinct from the {@code org:} prefix the API cap uses, and
     * carrying the capacity so a changed cap takes effect at once — see
     * {@link RateLimitConfig#capacitySuffix}.
     */
    private static String bucketKey(UUID orgId, int cap) {
        return "pdf:org:" + orgId + RateLimitConfig.capacitySuffix(cap);
    }

    private BucketProxy bucket(UUID orgId, int cap) {
        return proxyManager.builder()
                .build(bucketKey(orgId, cap), RateLimitConfig.perOrgDailyPdf(cap));
    }

    /** The org's cap, or null when uncapped. */
    private Integer capOf(UUID orgId) {
        return entitlements.resolve(orgId).pdfDailyMax();
    }

    /**
     * Reserve one document against the org's daily allowance.
     *
     * @throws ResponseStatusException 429 when the allowance is spent, or when
     *                                 the cap is zero
     */
    public void requireHeadroom(UUID orgId) {
        if (orgId == null) return;

        Integer cap = capOf(orgId);
        if (cap == null) return;

        if (cap <= 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Document generation is turned off for this workspace.");
        }

        try {
            ConsumptionProbe probe = bucket(orgId, cap).tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Daily document limit reached for this workspace ("
                                + cap + "/day). Try again in about "
                                + humanize(retryAfterSec) + ".");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("PDF quota check failed for org {}; allowing: {}", orgId, e.getMessage());
        }
    }

    /**
     * Give back a document reserved by {@link #requireHeadroom} when the work it
     * was reserved for failed. Best-effort: a lost refund costs the customer one
     * document, so it is not worth failing the request over.
     */
    public void refund(UUID orgId) {
        if (orgId == null) return;
        Integer cap = capOf(orgId);
        if (cap == null || cap <= 0) return;
        try {
            bucket(orgId, cap).addTokens(1);
        } catch (RuntimeException e) {
            log.warn("Could not refund PDF quota for org {}: {}", orgId, e.getMessage());
        }
    }

    /** How many documents remain today, or null when the org is uncapped. */
    public Long remainingToday(UUID orgId) {
        if (orgId == null) return null;
        Integer cap = capOf(orgId);
        if (cap == null) return null;
        if (cap <= 0) return 0L;
        try {
            return Math.max(0, bucket(orgId, cap).getAvailableTokens());
        } catch (RuntimeException e) {
            log.warn("Could not read PDF quota for org {}: {}", orgId, e.getMessage());
            return null;
        }
    }

    private static String humanize(long seconds) {
        if (seconds < 120) return seconds + " seconds";
        long minutes = seconds / 60;
        if (minutes < 120) return minutes + " minutes";
        return (minutes / 60) + " hours";
    }
}
