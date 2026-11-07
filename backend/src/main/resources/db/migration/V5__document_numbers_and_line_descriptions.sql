-- =====================================================================
-- V5: Gapless per-organization document numbers; order line descriptions
--
-- next_document_number(org, kind) increments a per-(org, kind) counter
-- inside the caller's transaction. The counter row stays locked until
-- commit, so numbers are unique and, because a rolled-back transaction
-- also rolls back its increment, gapless. Sequential invoice numbering is
-- a legal requirement in many jurisdictions (e.g. GST invoices in India).
-- The cost: document creation is serialised per organization and kind.
-- =====================================================================

CREATE TABLE document_counters (
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    kind            varchar(16) NOT NULL CHECK (kind IN ('ORDER', 'INVOICE', 'CREDIT_NOTE')),
    last_value      bigint      NOT NULL CHECK (last_value > 0),
    PRIMARY KEY (organization_id, kind)
);

CREATE FUNCTION next_document_number(p_org uuid, p_kind varchar) RETURNS bigint
LANGUAGE sql AS $$
    INSERT INTO document_counters (organization_id, kind, last_value)
    VALUES (p_org, p_kind, 1)
    ON CONFLICT (organization_id, kind)
    DO UPDATE SET last_value = document_counters.last_value + 1
    RETURNING last_value
$$;

-- An order line records what was sold, as the customer saw it, so the
-- invoice can be produced without reading the (since edited) catalogue.
ALTER TABLE order_lines ADD COLUMN description varchar(300);
UPDATE order_lines SET description = '(not recorded)' WHERE description IS NULL;
ALTER TABLE order_lines ALTER COLUMN description SET NOT NULL;

ALTER TABLE credit_notes ADD COLUMN issued_at timestamptz;
UPDATE credit_notes SET issued_at = created_at WHERE status = 'ISSUED' AND issued_at IS NULL;
ALTER TABLE credit_notes ADD CONSTRAINT credit_notes_issued_has_time
    CHECK (status <> 'ISSUED' OR issued_at IS NOT NULL);

-- A DRAFT invoice created by mistake must be discardable (DRAFT -> VOID)
-- without pretending it was ever issued. The V2 rule "non-draft implies
-- issued_at" made that impossible; the real rule is "ISSUED implies
-- issued_at". ISSUED -> VOID keeps issued_at (enforced by the V2 trigger).
ALTER TABLE invoices DROP CONSTRAINT invoices_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_issued_has_time
    CHECK (status <> 'ISSUED' OR issued_at IS NOT NULL);
