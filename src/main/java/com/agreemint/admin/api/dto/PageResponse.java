package com.agreemint.admin.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Paging envelope for the admin list endpoints.
 *
 * <p>The admin API previously had no paging convention at all — every list
 * returned a bare array, and {@code GET /orgs} returned every row in the table.
 * This is that convention: one shape for every admin list, carrying enough for a
 * client to render a pager without a second request.
 *
 * @param total total matching rows, not the size of {@code items}
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages
) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** For callers that have already mapped the content. */
    public static <T> PageResponse<T> of(Page<?> page, List<T> mapped) {
        return new PageResponse<>(mapped, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
