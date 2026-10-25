package com.apac.claims.shared.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * The page envelope returned by every list endpoint. Spring's {@code PageImpl} is never serialised directly
 * because its JSON shape is not a stable contract.
 *
 * @param page zero-based page number
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {

    public PageResponse {
        content = List.copyOf(content);
    }

    /** Wraps a page whose elements are already DTOs. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return of(page, Function.identity());
    }

    /** Maps each element of a page (typically entity to DTO, inside the transaction) and wraps the result. */
    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        List<T> mapped = page.getContent().stream().map(mapper).toList();
        return new PageResponse<>(mapped, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isLast());
    }
}
