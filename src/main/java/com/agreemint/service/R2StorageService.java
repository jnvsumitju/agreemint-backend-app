package com.agreemint.service;

import com.agreemint.config.R2Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;

/**
 * Thin wrapper around the R2 S3 clients used by the app. Kept deliberately
 * narrow — the rest of the codebase treats storage as an opaque upload/delete/
 * presign interface rather than dragging the AWS SDK through every service.
 *
 * <p>Uses two buckets (private {@code documents}, public {@code avatars})
 * configured via {@link R2Properties}; see that class for the rationale.
 */
@Service
public class R2StorageService {

    private static final Logger log = LoggerFactory.getLogger(R2StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final R2Properties props;

    public R2StorageService(S3Client r2S3Client, S3Presigner r2S3Presigner, R2Properties props) {
        this.s3 = r2S3Client;
        this.presigner = r2S3Presigner;
        this.props = props;
    }

    /** Upload bytes to the private documents bucket at {@code key}. */
    public void putDocument(String key, byte[] bytes, String contentType) {
        put(props.getBucketDocuments(), key, bytes, contentType);
    }

    /** Upload bytes to the public avatars bucket at {@code key}. Returns the
     *  permanent public URL clients can use as {@code <img src>}. */
    public String putPublic(String key, byte[] bytes, String contentType) {
        put(props.getBucketPublic(), key, bytes, contentType);
        return publicUrl(key);
    }

    /** Upload a template thumbnail to the PRIVATE thumbnails bucket. */
    public void putThumbnail(String key, byte[] bytes, String contentType) {
        put(props.getBucketThumbnails(), key, bytes, contentType);
    }

    /**
     * Upload to the WORLD-READABLE thumbnails bucket and return its permanent URL.
     *
     * <p>Only for first-party templates. A customer's template preview here
     * would be readable by anyone with the URL, and the bucket choice is the
     * only thing separating the two — hence the separate method rather than a
     * boolean on the one above.
     */
    public String putPublicThumbnail(String key, byte[] bytes, String contentType) {
        put(props.getBucketThumbnailsPublic(), key, bytes, contentType, thumbnailCacheControl());
        String base = props.getThumbnailsPublicBaseUrl();
        if (base == null || base.isBlank()) return null;
        return base.endsWith("/") ? base + key : base + "/" + key;
    }

    /** Short-lived read URL for a private thumbnail. */
    public URL presignThumbnailGet(String key) {
        return presignGet(props.getBucketThumbnails(), key);
    }

    /**
     * How long a browser and the CDN may hold a published thumbnail.
     *
     * <p>Only the public bucket gets this. Objects in the private buckets are
     * read through presigned URLs whose signature changes on every response, so
     * the URL is never the same twice and a cache directive on them would be
     * decoration — nothing could ever hit.
     *
     * <p>Clamped at zero into {@code no-cache} rather than emitting a negative
     * max-age, which is malformed and would be ignored, leaving the object with
     * whatever default the CDN picked. Setting the property to 0 is a plausible
     * way to ask for "don't cache this while I debug it", and it should mean
     * that.
     */
    String thumbnailCacheControl() {
        int seconds = props.getThumbnailCacheSeconds();
        return seconds <= 0 ? "no-cache" : "public, max-age=" + seconds;
    }

    /** Shared helper — not public because callers should route through the
     *  bucket-specific methods so we don't get the buckets mixed up. */
    private void put(String bucket, String key, byte[] bytes, String contentType) {
        put(bucket, key, bytes, contentType, null);
    }

    private void put(String bucket, String key, byte[] bytes, String contentType, String cacheControl) {
        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength((long) bytes.length);
        // Left off entirely rather than sent empty: S3 stores whatever string
        // it is given, and an empty Cache-Control header is not the same as no
        // header — some intermediaries treat it as uncacheable.
        if (cacheControl != null) req.cacheControl(cacheControl);
        s3.putObject(req.build(), RequestBody.fromBytes(bytes));
    }

    /**
     * Best-effort delete. We swallow {@link NoSuchKeyException} so a
     * missing object doesn't cascade into the caller's transaction — for
     * example, when rotating an avatar the new upload should succeed even
     * if the previous object was already cleaned up out-of-band.
     */
    public void deleteDocument(String key) {
        softDelete(props.getBucketDocuments(), key);
    }

    public void deletePublic(String key) {
        softDelete(props.getBucketPublic(), key);
    }

    private void softDelete(String bucket, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            // fine
        } catch (S3Exception e) {
            log.warn("R2 delete failed bucket={} key={}: {}", bucket, key, e.getMessage());
        }
    }

    /**
     * Open a read stream to a document-bucket object. Caller MUST close
     * the returned stream (typically Spring closes it after writing the
     * response body). Use this when you want to proxy bytes through the
     * backend rather than handing out a presigned URL — avoids CORS on
     * the R2 endpoint for browsers that preflight cross-origin fetches.
     */
    public ResponseInputStream<GetObjectResponse> openDocument(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(props.getBucketDocuments())
                .key(key)
                .build());
    }

    /**
     * Short-lived signed URL for a private-bucket object. TTL comes from
     * {@link R2Properties#getPresignTtlMinutes()} (default 5 minutes).
     * Retained for API-key callers (curl / server-to-server) where CORS
     * doesn't apply and a redirect is cheaper than a proxied byte stream.
     */
    /** How long a presigned URL from this service stays valid, in minutes. */
    public int presignTtlMinutes() {
        return Math.max(props.getPresignTtlMinutes(), 1);
    }

    public URL presignDocumentGet(String key) {
        return presignGet(props.getBucketDocuments(), key);
    }

    /** Shared signer. Bucket-specific wrappers keep callers from picking one. */
    private URL presignGet(String bucket, String key) {
        int ttl = presignTtlMinutes();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(ttl))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build())
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(req);
        return presigned.url();
    }

    /**
     * Resolve an object key back to its public URL. The R2 bucket must have
     * "Allow Access" enabled (or a custom domain mapped) for these URLs to
     * resolve in browsers.
     */
    public String publicUrl(String key) {
        String base = props.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("agreemint.r2.public-base-url is not configured");
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }

    /**
     * Inverse of {@link #publicUrl(String)} — given a URL we previously wrote
     * into e.g. {@code users.avatar_url}, recover the object key so we can
     * delete it on replacement. Returns {@code null} if the URL isn't one we
     * own (e.g. OAuth avatars from Google / GitHub).
     */
    public String keyFromPublicUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String base = props.getPublicBaseUrl();
        if (base == null || base.isBlank()) return null;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!url.startsWith(base + "/")) return null;
        return url.substring(base.length() + 1);
    }
}
