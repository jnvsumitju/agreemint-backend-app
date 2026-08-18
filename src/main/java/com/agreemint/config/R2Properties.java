package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare R2 (S3-compatible) storage config.
 *
 * <p>We use two buckets so we can publish one and keep the other private
 * without needing per-object ACLs or a CDN layer in front:
 * <ul>
 *   <li>{@link #bucketDocuments} — holds generated PDFs. Accessed via short
 *       TTL presigned URLs; the backend 302-redirects to them so the bytes
 *       never flow through the JVM.</li>
 *   <li>{@link #bucketPublic} — holds user / org avatars. Bucket must be
 *       configured public (R2 "Allow Access" or custom domain). The public
 *       URL is stored directly in {@code users.avatar_url} /
 *       {@code organizations.logo_url}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "agreemint.r2")
public class R2Properties {

    /** Cloudflare account id — used to build the S3 endpoint URL. */
    private String accountId;

    private String accessKeyId;
    private String secretAccessKey;

    /** Private bucket for generated PDFs. */
    private String bucketDocuments;

    /** Public bucket for avatars. */
    private String bucketPublic;

    /**
     * Public base URL of {@link #bucketPublic}. Typically either the default
     * {@code https://pub-<hash>.r2.dev} that Cloudflare exposes when "Allow
     * Access" is enabled, or a custom domain pointed at the bucket. We build
     * avatar URLs as {@code {publicBaseUrl}/{key}}.
     */
    private String publicBaseUrl;

    /**
     * Private bucket holding template thumbnails.
     *
     * <p>Separate from {@code documents} because the retention story differs:
     * a thumbnail is a derived, disposable image that can always be re-rendered
     * from the layout, while a generated document is the artifact a customer
     * relies on. Mixing them would make a lifecycle rule on one apply to the
     * other.
     */
    private String bucketThumbnails;

    /**
     * Public bucket for the first-party templates' thumbnails.
     *
     * <p>crixaa.com reads these with no credentials, so ONLY the Crixaa
     * publisher org's committed thumbnails ever land here — a customer's
     * template preview in a world-readable bucket would be a data leak, and the
     * bucket choice is the only thing standing between the two.
     */
    private String bucketThumbnailsPublic;

    /** Public base URL of {@link #bucketThumbnailsPublic}. */
    private String thumbnailsPublicBaseUrl;

    /** Minutes a presigned PDF URL is valid. Small — leaked URLs expire fast. */
    private int presignTtlMinutes = 5;

    /**
     * How long a published thumbnail may be cached, in seconds.
     *
     * <p>Tunable because the right value is a trade-off with no obviously
     * correct answer. The object key is stable, so a new thumbnail overwrites
     * the old one in place rather than getting a fresh URL — which means this
     * is also how long a staff member's edit can stay invisible on crixaa.com
     * after they commit it. An hour keeps the images out of the network tab for
     * repeat visitors without making a correction feel lost.
     */
    private int thumbnailCacheSeconds = 3600;

    public String getBucketThumbnails() { return bucketThumbnails; }
    public void setBucketThumbnails(String v) { this.bucketThumbnails = v; }

    public String getBucketThumbnailsPublic() { return bucketThumbnailsPublic; }
    public void setBucketThumbnailsPublic(String v) { this.bucketThumbnailsPublic = v; }

    public String getThumbnailsPublicBaseUrl() { return thumbnailsPublicBaseUrl; }
    public void setThumbnailsPublicBaseUrl(String v) { this.thumbnailsPublicBaseUrl = v; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

    public String getBucketDocuments() { return bucketDocuments; }
    public void setBucketDocuments(String bucketDocuments) { this.bucketDocuments = bucketDocuments; }

    public String getBucketPublic() { return bucketPublic; }
    public void setBucketPublic(String bucketPublic) { this.bucketPublic = bucketPublic; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public int getPresignTtlMinutes() { return presignTtlMinutes; }
    public void setPresignTtlMinutes(int presignTtlMinutes) { this.presignTtlMinutes = presignTtlMinutes; }

    public int getThumbnailCacheSeconds() { return thumbnailCacheSeconds; }
    public void setThumbnailCacheSeconds(int v) { this.thumbnailCacheSeconds = v; }

    /** Derived: {@code https://{accountId}.r2.cloudflarestorage.com}. */
    public String endpoint() {
        return "https://" + accountId + ".r2.cloudflarestorage.com";
    }

    /** True when the minimum required fields are configured. */
    public boolean isConfigured() {
        return accountId != null && !accountId.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }
}
