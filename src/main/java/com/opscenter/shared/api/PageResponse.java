package com.opscenter.shared.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Pagination envelope of every list endpoint (04-API §2.4).
 * <p>
 * Spring Data's {@code Page} is deliberately not exposed: its JSON shape is an implementation detail
 * and changes between versions, while this record is the published contract
 * ({@code items, page, size, totalItems, totalPages}).
 *
 * @param <T> item type
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Wraps a Spring Data page without transforming its content. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Wraps a Spring Data page while mapping entities to DTOs in one step. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
