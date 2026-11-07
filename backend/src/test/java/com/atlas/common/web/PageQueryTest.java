package com.atlas.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.common.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    private static final Map<String, String> SORTS = Map.of("name", "name", "createdAt", "created_at");

    @Test
    void appliesDefaults() {
        PageQuery q = PageQuery.of(null, null, null, SORTS, "createdAt,desc");
        assertEquals(0, q.page());
        assertEquals(20, q.size());
        assertEquals("created_at DESC", q.orderBy());
        assertEquals(0L, q.offset());
    }

    @Test
    void mapsApiFieldToColumnAndComputesOffset() {
        PageQuery q = PageQuery.of(3, 25, "name,asc", SORTS, "createdAt,desc");
        assertEquals("name ASC", q.orderBy());
        assertEquals(75L, q.offset());
        assertEquals("name ASC", PageQuery.of(0, 10, "name", SORTS, "createdAt").orderBy());
    }

    @Test
    void rejectsSqlInjectionThroughSort() {
        assertThrows(ApiException.class,
                () -> PageQuery.of(0, 10, "name; DROP TABLE products", SORTS, "name"));
        assertThrows(ApiException.class,
                () -> PageQuery.of(0, 10, "name,asc,extra", SORTS, "name"));
        assertThrows(ApiException.class,
                () -> PageQuery.of(0, 10, "name,sideways", SORTS, "name"));
        assertThrows(ApiException.class,
                () -> PageQuery.of(0, 10, "created_at", SORTS, "name"));   // column name is not an API field
    }

    @Test
    void enforcesPageBounds() {
        assertThrows(ApiException.class, () -> PageQuery.of(-1, 10, null, SORTS, "name"));
        assertThrows(ApiException.class, () -> PageQuery.of(0, 0, null, SORTS, "name"));
        assertThrows(ApiException.class, () -> PageQuery.of(0, 101, null, SORTS, "name"));
        assertEquals(100, PageQuery.of(0, 100, null, SORTS, "name").size());
    }

    @Test
    void computesTotalPages() {
        PageQuery q = PageQuery.of(0, 20, null, SORTS, "name");
        assertEquals(0, PageResponse.of(List.of(), q, 0).page().totalPages());
        assertEquals(1, PageResponse.of(List.of(), q, 20).page().totalPages());
        assertEquals(2, PageResponse.of(List.of(), q, 21).page().totalPages());
    }
}
