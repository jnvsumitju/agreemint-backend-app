package com.agreemint.pdf;

import java.util.UUID;

/**
 * What a render needs in order to make its output verifiable.
 *
 * <p>Both fields are known before rendering begins — the document row is
 * flushed and the code drawn first — which is what makes this possible at all.
 * A digest could not go here: writing a file's own hash into the file changes
 * the file, so the value would immediately stop being its hash. The digest is
 * therefore taken *afterwards*, over the finished bytes, and the identity that
 * goes *inside* is this.
 *
 * @param documentId the row this document belongs to
 * @param code       the short printable code, or null when none was issued
 * @param visible    whether to stamp the mark onto the page, as opposed to
 *                   recording it only in the PDF's metadata
 */
public record VerificationMark(UUID documentId, String code, boolean visible) {

    /** Metadata only — no visible change to the page. */
    public static VerificationMark hidden(UUID documentId, String code) {
        return new VerificationMark(documentId, code, false);
    }
}
