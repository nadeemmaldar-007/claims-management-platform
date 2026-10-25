package com.apac.claims.shared.api;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.apac.claims.shared.exception.ValidationFailedException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns the {@code page}, {@code size} and {@code sort} query parameters into a {@link Pageable} following the API
 * conventions: pages start at 0, the default size is 10 and is capped at 50, and sorting is limited to a whitelist
 * of fields per endpoint so that a client cannot sort by an arbitrary column.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 50;

    private PageRequests() {
    }

    /**
     * @param sort {@code field} or {@code field,asc|desc}; null or blank means {@code defaultSort}
     * @param allowedSortFields the fields this endpoint may be sorted by
     * @throws ValidationFailedException for a negative page, a size below 1, or a sort that is not allowed
     */
    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowedSortFields,
                              Sort defaultSort) {
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw ValidationFailedException.forField("page", "must be 0 or greater");
        }
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1) {
            throw ValidationFailedException.forField("size", "must be at least 1");
        }
        // A larger size is not an error: it is capped, so clients can ask for "as many as possible".
        return PageRequest.of(pageNumber, Math.min(pageSize, MAX_SIZE), parseSort(sort, allowedSortFields, defaultSort));
    }

    private static Sort parseSort(String sort, Set<String> allowed, Sort defaultSort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",", -1);
        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw ValidationFailedException.forField("sort", "must be one of " + String.join(", ",
                    new TreeSet<>(allowed)));
        }
        if (parts.length > 2) {
            throw ValidationFailedException.forField("sort", "must have the form field or field,asc or field,desc");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String requested = parts[1].trim().toLowerCase(Locale.ROOT);
            direction = switch (requested) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw ValidationFailedException.forField("sort", "direction must be asc or desc");
            };
        }
        return Sort.by(direction, field);
    }
}
