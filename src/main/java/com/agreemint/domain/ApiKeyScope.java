package com.agreemint.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fine-grained scopes for API keys. The wire name (e.g. {@code documents:generate})
 * is what customers see in the UI and docs; we map those to enum constants here.
 *
 * <p>Adding a new scope: append a constant below, set its {@link #wire} name,
 * and annotate the relevant {@code PublicApiController} endpoint with
 * {@code @PreAuthorize("hasAuthority('SCOPE_<wire>')")}.
 */
public enum ApiKeyScope {

    DOCUMENTS_GENERATE("documents:generate"),
    DOCUMENTS_READ("documents:read"),
    TEMPLATES_READ("templates:read"),
    WEBHOOKS_READ("webhooks:read"),
    WEBHOOKS_WRITE("webhooks:write");

    private final String wire;

    ApiKeyScope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public String authority() {
        return "SCOPE_" + wire;
    }

    public static ApiKeyScope fromWire(String s) {
        for (ApiKeyScope v : values()) {
            if (v.wire.equals(s)) return v;
        }
        throw new IllegalArgumentException("Unknown scope: " + s);
    }

    public static Set<String> allWireNames() {
        return Arrays.stream(values()).map(ApiKeyScope::wire).collect(Collectors.toSet());
    }
}
