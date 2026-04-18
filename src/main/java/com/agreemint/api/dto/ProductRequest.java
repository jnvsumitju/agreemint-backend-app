package com.agreemint.api.dto;

/**
 * Create or rename a product. Both fields are optional on update — only
 * supplied fields are applied.
 */
public record ProductRequest(String name, String description) {
}
