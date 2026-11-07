package com.atlas.graph.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.graph.schema.SchemaModel.Column;
import com.atlas.graph.schema.SchemaModel.Index;
import com.atlas.graph.schema.SchemaModel.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

    @Test
    void identicalSchemasHaveNoChanges() {
        SchemaModel a = SchemaModel.of(RelationshipClassifierTest.fixture());
        SchemaDiff.Result d = SchemaDiff.compare(a, SchemaModel.of(RelationshipClassifierTest.fixture()));
        assertTrue(d.identical());
    }

    @Test
    void reportsAddedRemovedAndChangedObjects() {
        List<Table> before = RelationshipClassifierTest.fixture();
        List<Table> after = new ArrayList<>();
        for (Table t : before) {
            if (t.name().equals("stock")) {
                continue;                                                    // table removed
            }
            if (t.name().equals("invoices")) {
                List<Column> cols = new ArrayList<>(t.columns());
                cols.add(new Column("delivery_note", 5, "character varying(500)", true, null));   // column added
                // the partial index becomes a plain unique index: a changed index AND a changed relationship kind
                t = new Table(t.name(), t.kind(), cols, t.constraints(),
                        List.of(new Index("ux_active", List.of("organization_id", "order_id"), true, false, null,
                                "CREATE UNIQUE INDEX ux_active (no predicate)")), t.triggers());
            }
            after.add(t);
        }
        after.add(new Table("audit", "TABLE", List.of(new Column("id", 1, "uuid", false, null)), List.of(), List.of(),
                List.of()));                                                  // table added
        SchemaDiff.Result d = SchemaDiff.compare(SchemaModel.of(before), SchemaModel.of(after));
        assertTrue(d.changes().stream().anyMatch(c -> c.objectType().equals("TABLE") && c.name().equals("stock")
                && c.change().equals("REMOVED")));
        assertTrue(d.changes().stream().anyMatch(c -> c.objectType().equals("TABLE") && c.name().equals("audit")
                && c.change().equals("ADDED")));
        assertTrue(d.changes().stream().anyMatch(c -> c.objectType().equals("COLUMN") && c.name().equals("delivery_note")
                && c.change().equals("ADDED") && c.after().contains("NULL")));
        assertTrue(d.changes().stream().anyMatch(c -> c.objectType().equals("INDEX") && c.change().equals("CHANGED")));
        assertTrue(d.changes().stream().anyMatch(c -> c.objectType().equals("RELATIONSHIP")
                && c.name().equals("invoices_order_fk") && c.change().equals("CHANGED")
                && c.before().contains("AT_MOST_ONE_ACTIVE") && c.after().contains("ONE_TO_ONE")), d.changes().toString());
        assertEquals(d.changes().size(), d.added() + d.removed() + d.changed());
    }
}
