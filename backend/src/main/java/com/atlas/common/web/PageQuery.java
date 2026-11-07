package com.atlas.common.web;

import com.atlas.common.error.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validated paging and sorting. The sort field is looked up in a whitelist
 * that maps API names to SQL columns, so client input never reaches the
 * ORDER BY clause directly (no SQL injection through ?sort=).
 */
public record PageQuery(int page, int size, String sortColumn, boolean ascending) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static PageQuery of(Integer page, Integer size, String sort,
                               Map<String, String> allowedSorts, String defaultSort) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.badRequest("page must be 0 or greater");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_SIZE);
        }
        String spec = (sort == null || sort.isBlank()) ? defaultSort : sort;
        String[] parts = spec.split(",", -1);
        if (parts.length > 2) {
            throw ApiException.badRequest("sort must look like 'field' or 'field,asc|desc'");
        }
        String column = allowedSorts.get(parts[0].trim());
        if (column == null) {
            throw ApiException.badRequest("Cannot sort by that field. Allowed: "
                    + String.join(", ", new TreeSet<>(allowedSorts.keySet())));
        }
        boolean ascending = parts.length < 2 || switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> true;
            case "desc" -> false;
            default -> throw ApiException.badRequest("sort direction must be 'asc' or 'desc'");
        };
        return new PageQuery(p, s, column, ascending);
    }

    /** Safe to splice into SQL: the column came from the whitelist. */
    public String orderBy() {
        return sortColumn + (ascending ? " ASC" : " DESC");
    }

    public long offset() {
        return (long) page * size;
    }
}
