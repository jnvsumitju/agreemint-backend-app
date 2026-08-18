package com.agreemint.service;

import com.agreemint.config.R2Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Cache headers on uploads, and which bucket gets them.
 *
 * <p>Only the published thumbnail carries one. Everything else in these buckets
 * is read through a presigned URL whose signature differs on every response, so
 * the URL is never the same twice and no cache could ever hit — a directive
 * there would look like an optimisation and do nothing.
 *
 * <p>The value is a trade-off rather than a tuning knob: the object key is
 * stable, so a re-render overwrites in place, and this is therefore also how
 * long a staff member's committed edit can stay invisible on crixaa.com. That
 * is why it is configurable and why the zero case has to mean something.
 */
class R2ThumbnailCacheTest {

    private S3Client s3;
    private R2Properties props;
    private R2StorageService storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        props = new R2Properties();
        props.setBucketDocuments("docs");
        props.setBucketPublic("pub");
        props.setBucketThumbnails("thumbs");
        props.setBucketThumbnailsPublic("thumbs-public");
        props.setThumbnailsPublicBaseUrl("https://thumbnails.crixaa.test");
        storage = new R2StorageService(s3, mock(S3Presigner.class), props);
    }

    private PutObjectRequest captureUpload() {
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        return req.getValue();
    }

    @Test
    void aPublishedThumbnailIsCacheableByBrowsersAndTheCdn() {
        storage.putPublicThumbnail("templates/free-gst-invoice-template.png", new byte[] { 1 }, "image/png");

        PutObjectRequest req = captureUpload();
        assertEquals("thumbs-public", req.bucket());
        assertEquals("public, max-age=3600", req.cacheControl());
    }

    @Test
    void thePrivateThumbnailGetsNoCacheControlAtAll() {
        storage.putThumbnail("templates/x.png", new byte[] { 1 }, "image/png");

        PutObjectRequest req = captureUpload();
        assertEquals("thumbs", req.bucket());
        // Absent, not empty. S3 stores whatever string it is handed, and an
        // empty Cache-Control is not the same as no header — some caches read
        // it as uncacheable.
        assertNull(req.cacheControl());
    }

    @Test
    void theOtherBucketsAreUnaffected() {
        // These predate the header and share the same private helper, so the
        // overload must not have leaked a default onto them.
        storage.putDocument("documents/a.pdf", new byte[] { 1 }, "application/pdf");
        assertNull(captureUpload().cacheControl());
    }

    @Test
    void theTtlIsConfigurable() {
        props.setThumbnailCacheSeconds(60);
        storage.putPublicThumbnail("templates/x.png", new byte[] { 1 }, "image/png");

        assertEquals("public, max-age=60", captureUpload().cacheControl());
    }

    @Test
    void zeroMeansDoNotCacheRatherThanCacheForever() {
        // "max-age=0" is a legitimate directive but a shared cache may still
        // serve it while revalidating; `no-cache` is the one that forces a
        // check every time, which is what someone setting this to 0 wants.
        props.setThumbnailCacheSeconds(0);
        storage.putPublicThumbnail("templates/x.png", new byte[] { 1 }, "image/png");

        assertEquals("no-cache", captureUpload().cacheControl());
    }

    @Test
    void aNegativeTtlDoesNotProduceAMalformedHeader() {
        // A typo in the env file should not emit `max-age=-1`, which is
        // malformed and gets ignored — leaving the object on whatever default
        // the CDN chose, i.e. silently the opposite of what was asked for.
        props.setThumbnailCacheSeconds(-1);
        storage.putPublicThumbnail("templates/x.png", new byte[] { 1 }, "image/png");

        assertEquals("no-cache", captureUpload().cacheControl());
    }

    @Test
    void theContentTypeStillSurvivesAlongsideTheCacheHeader() {
        // The overload rebuilt the request; losing content-type would make the
        // browser sniff, and R2 would serve it as application/octet-stream.
        storage.putPublicThumbnail("templates/x.png", new byte[] { 1, 2, 3 }, "image/png");

        PutObjectRequest req = captureUpload();
        assertEquals("image/png", req.contentType());
        assertEquals(3L, req.contentLength());
    }
}
