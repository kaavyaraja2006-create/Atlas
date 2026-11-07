-- =====================================================================
-- Schema guarantee assertions. Run against a freshly migrated database.
-- Each block raises an exception (failing the run) if a guarantee does
-- not hold. Used by scripts/verify-schema.sh and SchemaGuaranteesIT.
-- All rows created here are TEST DATA inside one transaction that is
-- rolled back at the end.
-- =====================================================================
\set ON_ERROR_STOP on
BEGIN;

-- Helper: assert that a statement fails with a given SQLSTATE.
CREATE FUNCTION pg_temp.expect_error(p_sql text, p_state text, p_label text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = p_state THEN
            RAISE NOTICE 'PASS  %', p_label;
            RETURN;
        END IF;
        RAISE EXCEPTION 'FAIL  % : expected SQLSTATE %, got % (%)', p_label, p_state, SQLSTATE, SQLERRM;
    END;
    RAISE EXCEPTION 'FAIL  % : statement succeeded but should have failed', p_label;
END $$;

CREATE FUNCTION pg_temp.expect_true(p_cond boolean, p_label text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_cond IS TRUE THEN RAISE NOTICE 'PASS  %', p_label;
    ELSE RAISE EXCEPTION 'FAIL  %', p_label; END IF;
END $$;

-- ---------- fixtures: two tenants ----------
INSERT INTO organizations (id, slug, name) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'tenant-a', 'Tenant A (test)'),
    ('b0000000-0000-0000-0000-000000000001', 'tenant-b', 'Tenant B (test)');
INSERT INTO users (id, email, display_name, password_hash) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'clerk@a.test', 'Clerk A', '{test}not-a-real-hash'),
    ('c0000000-0000-0000-0000-000000000002', 'approver@a.test', 'Approver A', '{test}not-a-real-hash');
INSERT INTO customers (id, organization_id, name) VALUES
    ('d0000000-0000-0000-0000-00000000000a', 'a0000000-0000-0000-0000-000000000001', 'Customer of A'),
    ('d0000000-0000-0000-0000-00000000000b', 'b0000000-0000-0000-0000-000000000001', 'Customer of B');
INSERT INTO products (id, organization_id, sku, name, unit_price, unit_cost) VALUES
    ('e0000000-0000-0000-0000-00000000000a', 'a0000000-0000-0000-0000-000000000001', 'P100', 'Widget', 5000, 3000),
    ('e0000000-0000-0000-0000-00000000000b', 'b0000000-0000-0000-0000-000000000001', 'P100', 'Widget', 7000, 4000);
INSERT INTO orders (id, organization_id, order_number, customer_id, created_by) VALUES
    ('f0000000-0000-0000-0000-00000000000a', 'a0000000-0000-0000-0000-000000000001', 'SO-1',
     'd0000000-0000-0000-0000-00000000000a', 'c0000000-0000-0000-0000-000000000001');

-- 1. Tenant isolation is enforced by composite foreign keys.
SELECT pg_temp.expect_error($q$
    INSERT INTO order_lines (organization_id, order_id, line_no, product_id, description, quantity, unit_price)
    VALUES ('a0000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-00000000000a', 1,
            'e0000000-0000-0000-0000-00000000000b', 'x', 1, 7000) $q$,
    '23503', 'order line cannot reference another tenant''s product');
SELECT pg_temp.expect_error($q$
    INSERT INTO orders (organization_id, order_number, customer_id, created_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', 'SO-2',
            'd0000000-0000-0000-0000-00000000000b', 'c0000000-0000-0000-0000-000000000001') $q$,
    '23503', 'order cannot reference another tenant''s customer');

-- 2. Generated line totals and domain checks.
INSERT INTO order_lines (id, organization_id, order_id, line_no, product_id, description, quantity, unit_price)
VALUES ('10000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
        'f0000000-0000-0000-0000-00000000000a', 1, 'e0000000-0000-0000-0000-00000000000a', 'P100 Widget', 3, 5000);
SELECT pg_temp.expect_true(
    (SELECT line_total = 15000 FROM order_lines WHERE id = '10000000-0000-0000-0000-000000000001'),
    'line_total is computed by the database (3 x 5000 = 15000)');
SELECT pg_temp.expect_error($q$
    UPDATE products SET unit_price = -1 WHERE sku = 'P100' $q$,
    '23514', 'negative price rejected');
SELECT pg_temp.expect_error($q$
    UPDATE orders SET status = 'CONFIRMED' WHERE order_number = 'SO-1' $q$,
    '23514', 'CONFIRMED order requires placed_at');

-- 3. Issued invoices are final.
INSERT INTO invoices (id, organization_id, invoice_number, order_id, total_amount)
VALUES ('20000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
        'INV-1', 'f0000000-0000-0000-0000-00000000000a', 15000);
INSERT INTO invoice_lines (organization_id, invoice_id, line_no, order_line_id, description, quantity, unit_price)
VALUES ('a0000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 1,
        '10000000-0000-0000-0000-000000000001', 'Widget', 3, 5000);
UPDATE invoices SET status = 'ISSUED', issued_at = now() WHERE invoice_number = 'INV-1';
SELECT pg_temp.expect_error($q$
    UPDATE invoices SET total_amount = 1500 WHERE invoice_number = 'INV-1' $q$,
    '23514', 'issued invoice amount cannot be rewritten');
SELECT pg_temp.expect_error($q$
    UPDATE invoice_lines SET unit_price = 500 WHERE description = 'Widget' $q$,
    '23514', 'issued invoice lines are frozen');
SELECT pg_temp.expect_error($q$
    INSERT INTO invoices (organization_id, invoice_number, order_id)
    VALUES ('a0000000-0000-0000-0000-000000000001', 'INV-2', 'f0000000-0000-0000-0000-00000000000a') $q$,
    '23505', 'only one non-void invoice per order');
INSERT INTO credit_notes (organization_id, credit_note_number, invoice_id, amount, reason)
VALUES ('a0000000-0000-0000-0000-000000000001', 'CN-1', '20000000-0000-0000-0000-000000000001',
        100, 'compensating adjustment (test)');
SELECT pg_temp.expect_true(true, 'credit note can compensate an issued invoice');
UPDATE invoices SET status = 'VOID' WHERE invoice_number = 'INV-1';
SELECT pg_temp.expect_true(
    (SELECT status = 'VOID' FROM invoices WHERE invoice_number = 'INV-1'),
    'issued invoice can be voided');
SELECT pg_temp.expect_error($q$
    UPDATE invoices SET status = 'ISSUED' WHERE invoice_number = 'INV-1' $q$,
    '23514', 'void invoice cannot be resurrected');

-- 4. Change events: append-only, one row per entity version, hash-chained.
INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                           entity_type, entity_id, entity_version, operation, after_state)
VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'USER',
        'c0000000-0000-0000-0000-000000000001', 'product',
        'e0000000-0000-0000-0000-00000000000a', 0, 'INSERT', '{"unit_price": 5000}');
INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                           entity_type, entity_id, entity_version, operation, changed_fields,
                           before_state, after_state, reason)
VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'USER',
        'c0000000-0000-0000-0000-000000000001', 'product',
        'e0000000-0000-0000-0000-00000000000a', 1, 'UPDATE', '{unit_price}',
        '{"unit_price": 5000}', '{"unit_price": 500}', 'price edit (the faulty change, test)');
INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                           entity_type, entity_id, entity_version, operation, after_state)
VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'USER',
        'c0000000-0000-0000-0000-000000000001', 'order_line',
        '10000000-0000-0000-0000-000000000001', 0, 'INSERT', '{"unit_price": 500, "quantity": 3}');

SELECT pg_temp.expect_true(
    (SELECT array_agg(chain_index ORDER BY chain_index) = '{1,2,3}'::bigint[]
       FROM change_events WHERE organization_id = 'a0000000-0000-0000-0000-000000000001'),
    'chain indexes are assigned contiguously by the database');
SELECT pg_temp.expect_true(
    verify_audit_chain('a0000000-0000-0000-0000-000000000001') IS NULL,
    'untampered audit chain verifies');
SELECT pg_temp.expect_error($q$
    UPDATE change_events SET reason = 'covering tracks' WHERE entity_version = 1 $q$,
    '42501', 'change_events rejects UPDATE');
SELECT pg_temp.expect_error($q$
    DELETE FROM change_events $q$,
    '42501', 'change_events rejects DELETE');
SELECT pg_temp.expect_error($q$
    TRUNCATE change_events CASCADE $q$,
    '42501', 'change_events rejects TRUNCATE');
SELECT pg_temp.expect_error($q$
    INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                               entity_type, entity_id, entity_version, operation, after_state)
    VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'SYSTEM', NULL,
            'product', 'e0000000-0000-0000-0000-00000000000a', 1, 'UPDATE', '{}') $q$,
    '23514', 'UPDATE event without before_state rejected');
SELECT pg_temp.expect_error($q$
    INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                               entity_type, entity_id, entity_version, operation,
                               before_state, after_state)
    VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(), 'SYSTEM', NULL,
            'product', 'e0000000-0000-0000-0000-00000000000a', 1, 'UPDATE', '{}', '{}') $q$,
    '23505', 'two events for the same entity version rejected');

-- 5. Provenance edges must connect real recorded versions.
INSERT INTO provenance_edges (organization_id, txn_id,
        source_entity_type, source_entity_id, source_entity_version, source_field,
        target_entity_type, target_entity_id, target_entity_version, target_field,
        relation, evidence)
VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(),
        'product', 'e0000000-0000-0000-0000-00000000000a', 1, 'unit_price',
        'order_line', '10000000-0000-0000-0000-000000000001', 0, 'unit_price',
        'DERIVED_VALUE', 'RECORDED_AT_WRITE');
SET CONSTRAINTS ALL IMMEDIATE;   -- force the deferred FK check now
SELECT pg_temp.expect_true(true, 'edge between recorded versions accepted');
SELECT pg_temp.expect_error($q$
    INSERT INTO provenance_edges (organization_id, txn_id,
        source_entity_type, source_entity_id, source_entity_version,
        target_entity_type, target_entity_id, target_entity_version, relation, evidence)
    VALUES ('a0000000-0000-0000-0000-000000000001', gen_random_uuid(),
        'product', 'e0000000-0000-0000-0000-00000000000a', 99,
        'order_line', '10000000-0000-0000-0000-000000000001', 0,
        'DERIVED_VALUE', 'RECORDED_AT_WRITE') $q$,
    '23503', 'edge from an unrecorded version is rejected (no fabricated edges)');
SELECT pg_temp.expect_error($q$
    UPDATE provenance_edges SET relation = 'COPIED_INTO' $q$,
    '42501', 'provenance_edges rejects UPDATE');
SELECT pg_temp.expect_true(
    (SELECT count(*) = 1 FROM provenance_edges
      WHERE source_entity_id = 'e0000000-0000-0000-0000-00000000000a'
        AND source_entity_version = 1),
    'the faulty product version (v1) has exactly one recorded consumer');

-- 6. Tamper evidence: someone with enough privilege bypasses the
--    append-only trigger and edits history. The chain must notice.
ALTER TABLE change_events DISABLE TRIGGER change_events_append_only;
UPDATE change_events SET after_state = '{"unit_price": 5000}'
 WHERE entity_type = 'product' AND entity_version = 1;
ALTER TABLE change_events ENABLE TRIGGER change_events_append_only;
SELECT pg_temp.expect_true(
    verify_audit_chain('a0000000-0000-0000-0000-000000000001') = 2,
    'tampered event #2 is detected by chain verification');

-- 7. Sessions: refresh-token rotation and reuse revocation (V4).
INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES
    ('30000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001',
     '31000000-0000-0000-0000-000000000001', sha256('token-1'), now() + interval '7 days');
SELECT pg_temp.expect_error($q$
    INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
    VALUES ('c0000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
            sha256('token-2'), now() + interval '7 days') $q$,
    '23505', 'only one live refresh token per family');
UPDATE refresh_tokens SET rotated_at = now() WHERE id = '30000000-0000-0000-0000-000000000001';
INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
VALUES ('c0000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
        sha256('token-2'), now() + interval '7 days');
SELECT pg_temp.expect_true(true, 'rotation: successor issued after predecessor is rotated');
SELECT pg_temp.expect_error($q$
    INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
    VALUES ('c0000000-0000-0000-0000-000000000001', gen_random_uuid(),
            convert_to('plaintext-token', 'UTF8'), now() + interval '1 day') $q$,
    '23514', 'refresh token must be stored as a 32-byte hash, not plaintext');
SELECT pg_temp.expect_error($q$
    UPDATE refresh_tokens SET revoked_at = now() WHERE revoke_reason IS NULL $q$,
    '23514', 'revocation requires a reason');
SELECT pg_temp.expect_true(
    revoke_token_family('31000000-0000-0000-0000-000000000001', 'REUSE_DETECTED') = 2,
    'reuse detection revokes every unrevoked token in the family (rotated + live)');
SELECT pg_temp.expect_true(
    (SELECT count(*) = 0 FROM refresh_tokens
      WHERE family_id = '31000000-0000-0000-0000-000000000001'
        AND rotated_at IS NULL AND revoked_at IS NULL),
    'no usable token remains in a revoked family');

-- 8. Login history is append-only and keeps unknown-email attempts.
INSERT INTO login_events (user_id, email_attempted, outcome, ip_address)
VALUES (NULL, 'nobody@a.test', 'BAD_CREDENTIALS', '10.0.0.7');
SELECT pg_temp.expect_error($q$
    INSERT INTO login_events (user_id, email_attempted, outcome)
    VALUES (NULL, 'nobody@a.test', 'SUCCESS') $q$,
    '23514', 'a successful login must name a user');
SELECT pg_temp.expect_error($q$
    DELETE FROM login_events $q$,
    '42501', 'login_events rejects DELETE');

-- 9. Idempotency keys are unique per tenant, and completion needs a response.
INSERT INTO idempotency_keys (organization_id, idempotency_key, user_id, method, path, request_hash)
VALUES ('a0000000-0000-0000-0000-000000000001', 'order-create-0001',
        'c0000000-0000-0000-0000-000000000001', 'POST', '/api/v1/orders', sha256('body'));
SELECT pg_temp.expect_error($q$
    INSERT INTO idempotency_keys (organization_id, idempotency_key, user_id, method, path, request_hash)
    VALUES ('a0000000-0000-0000-0000-000000000001', 'order-create-0001',
            'c0000000-0000-0000-0000-000000000001', 'POST', '/api/v1/orders', sha256('body')) $q$,
    '23505', 'same idempotency key cannot be claimed twice in a tenant');
INSERT INTO idempotency_keys (organization_id, idempotency_key, user_id, method, path, request_hash)
VALUES ('b0000000-0000-0000-0000-000000000001', 'order-create-0001',
        'c0000000-0000-0000-0000-000000000001', 'POST', '/api/v1/orders', sha256('body'));
SELECT pg_temp.expect_true(true, 'same key is independent in another tenant');
SELECT pg_temp.expect_error($q$
    UPDATE idempotency_keys SET status = 'COMPLETED'
     WHERE idempotency_key = 'order-create-0001'
       AND organization_id = 'a0000000-0000-0000-0000-000000000001' $q$,
    '23514', 'COMPLETED key must store the response');

-- 10. Document numbers are per organization and kind, sequential, and gapless (V5).
SELECT pg_temp.expect_true(
    next_document_number('a0000000-0000-0000-0000-000000000001', 'INVOICE') = 1
    AND next_document_number('a0000000-0000-0000-0000-000000000001', 'INVOICE') = 2
    AND next_document_number('a0000000-0000-0000-0000-000000000001', 'ORDER') = 1
    AND next_document_number('b0000000-0000-0000-0000-000000000001', 'INVOICE') = 1,
    'document numbers are sequential per organization and kind');
SAVEPOINT before_rollback;
DO $$ BEGIN PERFORM next_document_number('a0000000-0000-0000-0000-000000000001', 'INVOICE'); END $$;
ROLLBACK TO SAVEPOINT before_rollback;
SELECT pg_temp.expect_true(
    next_document_number('a0000000-0000-0000-0000-000000000001', 'INVOICE') = 3,
    'a rolled-back number is reused, so numbering stays gapless');
SELECT pg_temp.expect_error($q$
    SELECT next_document_number('a0000000-0000-0000-0000-000000000001', 'RECEIPT') $q$,
    '23514', 'unknown document kind rejected');
SELECT pg_temp.expect_error($q$
    INSERT INTO order_lines (organization_id, order_id, line_no, product_id, quantity, unit_price)
    VALUES ('a0000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-00000000000a', 9,
            'e0000000-0000-0000-0000-00000000000a', 1, 1) $q$,
    '23502', 'order line requires a description');

SELECT pg_temp.expect_error($q$
    UPDATE invoices SET status = 'ISSUED', issued_at = NULL WHERE invoice_number = 'INV-1' $q$,
    '23514', 'an ISSUED invoice must have issued_at');

-- 11. Recovery incidents and immutable plans (V6).
INSERT INTO recovery_incidents (id, organization_id, incident_number, faulty_change_event_id, title, reason, opened_by)
SELECT '40000000-0000-0000-0000-000000000001', organization_id, 'REC-000001', id, 'Mistyped price', 'P100 entered as 500',
       'c0000000-0000-0000-0000-000000000001'
  FROM change_events WHERE entity_type = 'product' AND entity_version = 1
   AND organization_id = 'a0000000-0000-0000-0000-000000000001';
SELECT pg_temp.expect_true(true, 'incident references the faulty change event');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_incidents (organization_id, incident_number, faulty_change_event_id, title, reason, opened_by)
    SELECT organization_id, 'REC-000002', id, 'dup', 'dup', 'c0000000-0000-0000-0000-000000000001'
      FROM change_events WHERE entity_type = 'product' AND entity_version = 1
       AND organization_id = 'a0000000-0000-0000-0000-000000000001' $q$,
    '23505', 'only one active incident per faulty change');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_incidents (organization_id, incident_number, faulty_change_event_id, title, reason, opened_by)
    SELECT 'b0000000-0000-0000-0000-000000000001', 'REC-000001', id, 'x', 'x', 'c0000000-0000-0000-0000-000000000001'
      FROM change_events WHERE entity_type = 'product' AND entity_version = 1
       AND organization_id = 'a0000000-0000-0000-0000-000000000001' $q$,
    '23503', 'an incident cannot point at another tenant''s change event');
INSERT INTO recovery_plans (id, organization_id, incident_id, plan_number, fingerprint, risk, requires_approval,
                            step_count, manual_step_count, impact, plan, created_by)
VALUES ('41000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000001', 1, 'sha256:' || repeat('a', 64), 'LOW', false, 3, 0,
        '{}', '{"steps":[]}', 'c0000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_plans (organization_id, incident_id, plan_number, fingerprint, risk, requires_approval,
                                step_count, manual_step_count, impact, plan, created_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 2,
            'sha256:' || repeat('b', 64), 'LOW', false, 0, 0, '{}', '{}', 'c0000000-0000-0000-0000-000000000001') $q$,
    '23505', 'only one PROPOSED plan per incident');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET plan = '{"steps":["tampered"]}' WHERE plan_number = 1 $q$,
    '23514', 'plan content is immutable');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET status = 'EXECUTED' WHERE plan_number = 1 $q$,
    '23514', 'a plan cannot be executed without approval');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET status = 'APPROVED' WHERE plan_number = 1 $q$,
    '23514', 'a plan cannot be marked APPROVED without an approval decision (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, decided_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'APPROVED',
            'sha256:' || repeat('a', 64), 'c0000000-0000-0000-0000-000000000001') $q$,
    '42501', 'the author of a plan cannot approve it (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, decided_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'APPROVED',
            'sha256:' || repeat('c', 64), 'c0000000-0000-0000-0000-000000000002') $q$,
    '23514', 'an approval must name the plan''s fingerprint (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, decided_by, comment)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'REJECTED',
            'sha256:' || repeat('a', 64), 'c0000000-0000-0000-0000-000000000002', '  ') $q$,
    '23514', 'a rejection needs a reason (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_actions (organization_id, plan_id, step_no, action, entity_type, entity_id, expected_version,
                                  status, detail, result_version, txn_id, executed_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 1, 'UPDATE_FIELDS',
            'product', gen_random_uuid(), 1, 'DONE', 'x', 2, gen_random_uuid(), 'c0000000-0000-0000-0000-000000000002') $q$,
    '23514', 'steps cannot be recorded before the plan is executing (V7)');
INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, decided_by, comment)
VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'APPROVED',
        'sha256:' || repeat('a', 64), 'c0000000-0000-0000-0000-000000000002', 'checked');
UPDATE recovery_plans SET status = 'APPROVED' WHERE plan_number = 1;
SELECT pg_temp.expect_true(true, 'PROPOSED -> APPROVED allowed with an approval by someone else');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, decided_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'APPROVED',
            'sha256:' || repeat('a', 64), 'c0000000-0000-0000-0000-000000000002') $q$,
    '23505', 'one approval per plan (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_decisions SET comment = 'edited' $q$,
    '42501', 'decisions are append-only (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET status = 'EXECUTED' WHERE plan_number = 1 $q$,
    '23514', 'EXECUTED only through EXECUTING (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET status = 'PROPOSED' WHERE plan_number = 1 $q$,
    '23514', 'an approved plan cannot go back to proposed');
SELECT pg_temp.expect_error($q$
    DELETE FROM recovery_plans $q$,
    '42501', 'plans cannot be deleted');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_plans (organization_id, incident_id, plan_number, fingerprint, risk, requires_approval,
                                step_count, manual_step_count, impact, plan, created_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 3,
            'md5:abc', 'LOW', false, 0, 0, '{}', '{}', 'c0000000-0000-0000-0000-000000000001') $q$,
    '23514', 'fingerprint must be a sha256 digest');
SELECT pg_temp.expect_true(
    next_document_number('a0000000-0000-0000-0000-000000000001', 'INCIDENT') = 1,
    'incidents get their own number series');

-- 12. Execution and verification records (V7).
UPDATE recovery_plans SET status = 'EXECUTING' WHERE plan_number = 1;
SELECT pg_temp.expect_true(true, 'APPROVED -> EXECUTING allowed');
INSERT INTO recovery_actions (organization_id, plan_id, step_no, action, entity_type, entity_id, expected_version,
                              status, detail, result_version, txn_id, executed_by)
VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 1, 'RESTORE_FIELDS',
        'product', 'e0000000-0000-0000-0000-000000000001', 1, 'DONE', 'Wrote version 2', 2, gen_random_uuid(),
        'c0000000-0000-0000-0000-000000000002'),
       ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 2, 'MANUAL_REVIEW',
        'order_line', gen_random_uuid(), 0, 'AWAITING_ACKNOWLEDGEMENT', 'conflict', NULL, gen_random_uuid(),
        'c0000000-0000-0000-0000-000000000002');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_actions (organization_id, plan_id, step_no, action, entity_type, entity_id, expected_version,
                                  status, detail, result_version, txn_id, executed_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 1, 'RESTORE_FIELDS',
            'product', gen_random_uuid(), 1, 'DONE', 'again', 3, gen_random_uuid(),
            'c0000000-0000-0000-0000-000000000002') $q$,
    '23505', 'a step is recorded once (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_actions (organization_id, plan_id, step_no, action, entity_type, entity_id, expected_version,
                                  status, detail, txn_id, executed_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 3, 'UPDATE_FIELDS',
            'order', gen_random_uuid(), 1, 'DONE', 'no result', gen_random_uuid(),
            'c0000000-0000-0000-0000-000000000002') $q$,
    '23514', 'a DONE step names what it produced (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_actions SET status = 'FAILED' WHERE step_no = 1 $q$,
    '23514', 'a recorded outcome cannot be rewritten (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_actions SET status = 'ACKNOWLEDGED' WHERE step_no = 2 $q$,
    '23514', 'acknowledging needs who, when and what (V7)');
UPDATE recovery_actions SET status = 'ACKNOWLEDGED', acknowledged_by = 'c0000000-0000-0000-0000-000000000002',
       acknowledged_at = now(), acknowledgement = 'kept the new price'
 WHERE step_no = 2;
SELECT pg_temp.expect_true(true, 'AWAITING_ACKNOWLEDGEMENT -> ACKNOWLEDGED allowed');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_actions SET acknowledgement = 'changed my mind' WHERE step_no = 2 $q$,
    '23514', 'an acknowledgement cannot be edited (V7)');
SELECT pg_temp.expect_error($q$
    DELETE FROM recovery_actions $q$,
    '42501', 'actions cannot be deleted (V7)');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_plans SET status = 'APPROVED' WHERE plan_number = 1 $q$,
    '23514', 'an executing plan cannot go back (V7)');
UPDATE recovery_plans SET status = 'EXECUTED' WHERE plan_number = 1;
SELECT pg_temp.expect_true(true, 'EXECUTING -> EXECUTED allowed');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_verifications (organization_id, plan_id, outcome, check_count, failed_count, pending_manual,
                                        checks, verified_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'PASSED', 5, 1, 0,
            '[]', 'c0000000-0000-0000-0000-000000000002') $q$,
    '23514', 'a verification with a failed check cannot be PASSED (V7)');
SELECT pg_temp.expect_error($q$
    INSERT INTO recovery_verifications (organization_id, plan_id, outcome, check_count, failed_count, pending_manual,
                                        checks, verified_by)
    VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'PASSED', 5, 0, 1,
            '[]', 'c0000000-0000-0000-0000-000000000002') $q$,
    '23514', 'a verification with a pending manual step cannot be PASSED (V7)');
INSERT INTO recovery_verifications (organization_id, plan_id, outcome, check_count, failed_count, pending_manual,
                                    checks, verified_by)
VALUES ('a0000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'PASSED', 5, 0, 0,
        '[]', 'c0000000-0000-0000-0000-000000000002');
SELECT pg_temp.expect_error($q$
    UPDATE recovery_verifications SET outcome = 'FAILED', failed_count = 1 $q$,
    '42501', 'verifications are append-only (V7)');

DO $$ BEGIN RAISE NOTICE 'ALL SCHEMA ASSERTIONS PASSED'; END $$;
ROLLBACK;
