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

    /** Minutes a presigned PDF URL is valid. Small — leaked URLs expire fast. */
    private int presignTtlMinutes = 5;

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
