package com.agreemint.api.dto;

import java.util.List;

public record CreateWebhookRequest(String url, List<String> events) {}
