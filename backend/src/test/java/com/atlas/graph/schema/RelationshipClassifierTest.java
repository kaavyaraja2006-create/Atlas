package com.atlas.graph.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.graph.schema.SchemaModel.Column;
import com.atlas.graph.schema.SchemaModel.Constraint;
import com.atlas.graph.schema.SchemaModel.Index;
import com.atlas.graph.schema.SchemaModel.Relationship;
import com.atlas.graph.schema.SchemaModel.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Relationship kinds from declared keys only, on small hand-made schemas whose answer is known. */
class RelationshipClassifierTest {

    static Column col(String name, int pos) {
        return new Column(name, pos, "uuid", false, null);
    }

    static Constraint pk(String table, String... cols) {
        return new Constraint(table + "_pkey", "PRIMARY_KEY", List.of(cols), "PRIMARY KEY", null, List.of(), false, true);
    }

    static Constraint fk(String name, List<String> cols, String to, List<String> toCols) {
        return new Constraint(name, "FOREIGN_KEY", cols, "FOREIGN KEY", to, toCols, false, true);
    }

    static Constraint unique(String name, String... cols) {
        return new Constraint(name, "UNIQUE", List.of(cols), "UNIQUE", null, List.of(), false, true);
    }

    static Table table(String name, List<String> columns, List<Constraint> cons, List<Index> idx) {
        List<Column> cs = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            cs.add(col(columns.get(i), i + 1));
        }
        return new Table(name, "TABLE", cs, cons, idx, List.of());
    }

    static Map<String, Relationship> byConstraint(List<Relationship> rs) {
        return rs.stream().collect(Collectors.toMap(Relationship::constraint, Function.identity()));
    }

    /** A miniature of ATLAS: tenants, orders, invoices (one active per order), stock (1:1), a junction. */
    static List<Table> fixture() {
        return List.of(
                table("organizations", List.of("id"), List.of(pk("organizations", "id")), List.of()),
                table("orders", List.of("organization_id", "id"),
                        List.of(pk("orders", "id"), unique("orders_org_id", "organization_id", "id"),
                                fk("orders_org_fk", List.of("organization_id"), "organizations", List.of("id"))), List.of()),
                table("invoices", List.of("organization_id", "id", "order_id", "status"),
                        List.of(pk("invoices", "id"),
                                fk("invoices_order_fk", List.of("organization_id", "order_id"), "orders",
                                        List.of("organization_id", "id"))),
                        List.of(new Index("ux_active", List.of("organization_id", "order_id"), true, false,
                                "((status)::text <> 'VOID'::text)", "CREATE UNIQUE INDEX ..."))),
                table("stock", List.of("organization_id", "id", "order_id"),
                        List.of(pk("stock", "id"), unique("stock_one", "organization_id", "order_id"),
                                fk("stock_order_fk", List.of("organization_id", "order_id"), "orders",
                                        List.of("organization_id", "id"))), List.of()),
                table("chain_heads", List.of("organization_id", "last"),
                        List.of(pk("chain_heads", "organization_id"),
                                fk("heads_org_fk", List.of("organization_id"), "organizations", List.of("id"))), List.of()),
                table("roles", List.of("code"), List.of(pk("roles", "code")), List.of()),
                table("permissions", List.of("code"), List.of(pk("permissions", "code")), List.of()),
                table("role_permissions", List.of("role_code", "permission_code"),
                        List.of(pk("role_permissions", "role_code", "permission_code"),
                                fk("rp_role", List.of("role_code"), "roles", List.of("code")),
                                fk("rp_perm", List.of("permission_code"), "permissions", List.of("code"))), List.of()),
                // Has its own attribute (status): an entity, not a pure junction.
                table("members", List.of("organization_id", "user_code", "status"),
                        List.of(pk("members", "organization_id", "user_code"),
                                fk("m_org", List.of("organization_id"), "organizations", List.of("id")),
                                fk("m_role", List.of("user_code"), "roles", List.of("code"))), List.of()));
    }

    @Test
    void oneToManyIsTheDefault() {
        Relationship r = byConstraint(RelationshipClassifier.classify(fixture())).get("orders_org_fk");
        assertEquals("ONE_TO_MANY", r.kind());
        assertEquals("DECLARED", r.evidence());
        assertEquals("orders", r.from());
        assertEquals("organizations", r.to());
    }

    @Test
    void aPartialUniqueIndexIsAtMostOneActiveNotOneToOne() {
        Relationship r = byConstraint(RelationshipClassifier.classify(fixture())).get("invoices_order_fk");
        assertEquals("AT_MOST_ONE_ACTIVE", r.kind());
        assertTrue(r.note().contains("VOID"), r.note());
    }

    @Test
    void aUniqueConstraintOrAPrimaryKeyOnTheForeignKeyIsOneToOne() {
        Map<String, Relationship> m = byConstraint(RelationshipClassifier.classify(fixture()));
        assertEquals("ONE_TO_ONE", m.get("stock_order_fk").kind());
        assertEquals("ONE_TO_ONE", m.get("heads_org_fk").kind());
        assertTrue(m.get("heads_org_fk").note().contains("primary key"));
    }

    @Test
    void manyToManyIsInferredOnlyForAPureJunction() {
        List<Relationship> rs = RelationshipClassifier.classify(fixture());
        List<Relationship> m2m = rs.stream().filter(r -> r.kind().equals("MANY_TO_MANY")).toList();
        assertEquals(1, m2m.size(), m2m.toString());
        Relationship r = m2m.get(0);
        assertEquals("INFERRED", r.evidence());
        assertEquals("role_permissions", r.via());
        assertEquals(java.util.Set.of("roles", "permissions"), java.util.Set.of(r.from(), r.to()));
        // The junction's own foreign keys are still reported, as declared one-to-many.
        assertEquals("ONE_TO_MANY", byConstraint(rs).get("rp_role").kind());
    }

    @Test
    void notValidConstraintsAreFlagged() {
        Table t = table("x", List.of("id", "org"), List.of(pk("x", "id"),
                new Constraint("x_org", "FOREIGN_KEY", List.of("org"), "FOREIGN KEY", "organizations", List.of("id"), false, false)),
                List.of());
        Relationship r = RelationshipClassifier.classify(List.of(t)).get(0);
        assertTrue(r.note().contains("NOT VALID"), r.note());
    }

    @Test
    void sameInputSameOutputInAnyTableOrder() {
        List<Table> reversed = new ArrayList<>(fixture());
        java.util.Collections.reverse(reversed);
        assertEquals(SchemaModel.of(fixture()), SchemaModel.of(reversed));
    }
}
