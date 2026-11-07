-- =====================================================================
-- V2: Commerce domain (catalogue -> orders -> invoices, plus inventory)
--
-- This domain is chosen because it produces real derived data:
--   products.unit_price  --priced-->      order_lines.unit_price
--   order_lines          --aggregated-->  orders.total_amount
--   order_lines          --billed-->      invoice_lines
--   invoice_lines        --aggregated-->  invoices.total_amount
-- inventory_items references products by FK but is valued at unit_cost,
-- so a price error does NOT propagate to it. It is deliberately kept as
-- the "FK is not derivation" case for the recovery engine (ADR-0003).
-- =====================================================================

CREATE TABLE customers (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid         NOT NULL REFERENCES organizations (id),
    name            varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    email           varchar(320),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id)
);

CREATE TABLE products (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    sku             varchar(64)   NOT NULL,
    name            varchar(200)  NOT NULL,
    unit_price      numeric(19,4) NOT NULL CHECK (unit_price >= 0),
    unit_cost       numeric(19,4) NOT NULL CHECK (unit_cost >= 0),
    active          boolean       NOT NULL DEFAULT true,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, sku),
    UNIQUE (organization_id, id)
);

CREATE TABLE orders (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    order_number    varchar(32)   NOT NULL,
    customer_id     uuid          NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    total_amount    numeric(19,4) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    created_by      uuid          NOT NULL REFERENCES users (id),
    placed_at       timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, order_number),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, customer_id) REFERENCES customers (organization_id, id),
    CHECK (status <> 'CONFIRMED' OR placed_at IS NOT NULL)
);
CREATE INDEX ix_orders_org_customer ON orders (organization_id, customer_id);
CREATE INDEX ix_orders_org_created  ON orders (organization_id, created_at DESC);

CREATE TABLE order_lines (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL,
    order_id        uuid          NOT NULL,
    line_no         integer       NOT NULL CHECK (line_no > 0),
    product_id      uuid          NOT NULL,
    quantity        integer       NOT NULL CHECK (quantity > 0),
    -- Snapshot of products.unit_price at the time the line was priced.
    -- Which product version it came from is recorded in provenance_edges.
    unit_price      numeric(19,4) NOT NULL CHECK (unit_price >= 0),
    line_total      numeric(19,4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    version         bigint        NOT NULL DEFAULT 0,
    UNIQUE (order_id, line_no),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, order_id)   REFERENCES orders (organization_id, id),
    FOREIGN KEY (organization_id, product_id) REFERENCES products (organization_id, id)
);
CREATE INDEX ix_order_lines_org_product ON order_lines (organization_id, product_id);

CREATE TABLE invoices (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL,
    invoice_number  varchar(32)   NOT NULL,
    order_id        uuid          NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'ISSUED', 'VOID')),
    total_amount    numeric(19,4) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    issued_at       timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, invoice_number),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, order_id) REFERENCES orders (organization_id, id),
    CHECK (status = 'DRAFT' OR issued_at IS NOT NULL)
);
-- At most one non-void invoice per order.
CREATE UNIQUE INDEX ux_invoices_active_per_order
    ON invoices (organization_id, order_id) WHERE status <> 'VOID';

CREATE TABLE invoice_lines (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL,
    invoice_id      uuid          NOT NULL,
    line_no         integer       NOT NULL CHECK (line_no > 0),
    order_line_id   uuid          NOT NULL,
    description     varchar(300)  NOT NULL,
    quantity        integer       NOT NULL CHECK (quantity > 0),
    unit_price      numeric(19,4) NOT NULL CHECK (unit_price >= 0),
    line_total      numeric(19,4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    UNIQUE (invoice_id, line_no),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, invoice_id)    REFERENCES invoices (organization_id, id),
    FOREIGN KEY (organization_id, order_line_id) REFERENCES order_lines (organization_id, id)
);

-- Compensating document: an ISSUED invoice is never rewritten; an
-- over/under-billing is corrected by a credit note (or a new invoice).
CREATE TABLE credit_notes (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid          NOT NULL,
    credit_note_number varchar(32)   NOT NULL,
    invoice_id         uuid          NOT NULL,
    amount             numeric(19,4) NOT NULL CHECK (amount > 0),
    reason             varchar(500)  NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT', 'ISSUED')),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    version            bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, credit_note_number),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, invoice_id) REFERENCES invoices (organization_id, id)
);

CREATE TABLE inventory_items (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid        NOT NULL,
    product_id        uuid        NOT NULL,
    quantity_on_hand  integer     NOT NULL CHECK (quantity_on_hand >= 0),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, product_id),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, product_id) REFERENCES products (organization_id, id)
);

-- ---------------------------------------------------------------------
-- Issued invoices are legally final: only the transition ISSUED -> VOID
-- (and updated_at/version bookkeeping) is permitted. Their lines are
-- frozen once the invoice leaves DRAFT.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_invoices_guard_issued() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'VOID' THEN
        RAISE EXCEPTION 'invoice % is VOID and cannot be modified', OLD.invoice_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ISSUED' THEN
        IF NEW.status NOT IN ('ISSUED', 'VOID')
           OR NEW.total_amount   IS DISTINCT FROM OLD.total_amount
           OR NEW.order_id       IS DISTINCT FROM OLD.order_id
           OR NEW.invoice_number IS DISTINCT FROM OLD.invoice_number
           OR NEW.issued_at      IS DISTINCT FROM OLD.issued_at THEN
            RAISE EXCEPTION 'invoice % is ISSUED; only voiding is allowed (use a credit note)',
                OLD.invoice_number USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER invoices_guard_issued
    BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION trg_invoices_guard_issued();

CREATE FUNCTION trg_invoice_lines_guard_final() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status varchar(16);
BEGIN
    SELECT status INTO v_status FROM invoices
     WHERE id = COALESCE(NEW.invoice_id, OLD.invoice_id);
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'lines of a % invoice cannot be changed', v_status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER invoice_lines_guard_final
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION trg_invoice_lines_guard_final();
