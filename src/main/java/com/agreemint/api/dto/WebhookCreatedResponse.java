package com.agreemint.api.dto;

/** One-time response at webhook creation time — carries the raw signing secret. */
public record WebhookCreatedResponse(WebhookResponse webhook, String secret) {}
