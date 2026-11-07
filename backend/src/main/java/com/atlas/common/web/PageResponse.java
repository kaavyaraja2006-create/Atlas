package com.atlas.common.web;

import java.util.List;

public record PageResponse<T>(List<T> items, PageMeta page) {

    public record PageMeta(int number, int size, long totalItems, int totalPages) {
    }

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalItems) {
        int totalPages = (int) ((totalItems + query.size() - 1) / query.size());
        return new PageResponse<>(List.copyOf(items),
                new PageMeta(query.page(), query.size(), totalItems, totalPages));
    }
}
