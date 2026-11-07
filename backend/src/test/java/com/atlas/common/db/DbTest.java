package com.atlas.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DbTest {

    @Test
    void escapesLikeWildcardsInUserSearch() {
        assertEquals("%widget%", Db.containsPattern("widget"));
        assertEquals("%100\\%%", Db.containsPattern("100%"));
        assertEquals("%a\\_b%", Db.containsPattern("a_b"));
        assertEquals("%c:\\\\x%", Db.containsPattern("c:\\x"));
    }
}
