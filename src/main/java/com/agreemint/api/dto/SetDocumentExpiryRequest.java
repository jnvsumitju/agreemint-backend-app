package com.agreemint.api.dto;

import java.time.Instant;

/**
 * Set or clear a document's expiration date.
 *
 * @param expiresAt the instant the document should expire, or {@code null} to
 *                  remove the expiry. Null is a meaningful value here rather
 *                  than a missing one, which is why this is not {@code @NotNull}
 *                  — "no expiration" is a state a caller has to be able to
 *                  return a document to.
 *
 *                  <p>An instant, not a date. Users pick a calendar day, so the
 *                  console resolves that day to an explicit end-of-day in UTC
 *                  before sending it; doing that conversion client-side keeps
 *                  the server from having to guess a timezone it does not store.
 */
public record SetDocumentExpiryRequest(Instant expiresAt) {}
