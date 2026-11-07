// Visual check of the built SPA in a real browser.
//
// The API is NOT running here: Playwright intercepts /api/v1/** and answers
// with the clearly fake FIXTURE data below, which exists only in this script
// and never ships in the app. Purpose: look at layout, states and theming.
//
// With --a11y, every state is also checked with axe-core against WCAG 2.1 A/AA
// rules, and any violation fails the run.
//
// Usage: npm run build && node e2e/screenshots.mjs <output-dir> [--a11y]
import { readFileSync } from 'node:fs';
import { cspFor } from './csp-policy.mjs';
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';

// Resolves the machine's Playwright install (or a local devDependency) via require().
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const A11Y = process.argv.includes('--a11y');
const AxeBuilder = A11Y ? require('@axe-core/playwright').default : null;
const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

const OUT = process.argv.slice(2).find((a) => !a.startsWith('--')) ?? 'e2e/out';
mkdirSync(OUT, { recursive: true });
const PORT = 4173;
const BASE = `http://localhost:${PORT}`;
const SCHEMA_V15 = JSON.parse(readFileSync(new URL('./fixtures/schema-v15.json', import.meta.url), 'utf8'));
const snapshotV15 = { id: 'fixture-snapshot', flywayVersion: '15', sha256: 'f'.repeat(64), tableCount: SCHEMA_V15.tables.length,
  relationshipCount: SCHEMA_V15.relationships.length, capturedAt: '2026-11-07T00:00:00Z' };
const CSP = cspFor(readFileSync(new URL('../dist/index.html', import.meta.url), 'utf8'));

// ------------------------------------------------------------------ FIXTURES (fake, labelled)
const ORG = 'aaaaaaaa-0000-4000-8000-000000000001';
const now = '2026-11-05T10:30:00Z';
const org = { id: ORG, slug: 'demo-fixture', name: 'Fixture Traders (demo)', currency: 'INR', status: 'ACTIVE',
  createdAt: now, version: 0, myRoles: ['ORGANIZATION_ADMIN'] };
const perms = ['org.manage', 'member.manage', 'catalogue.read', 'catalogue.write', 'customer.read', 'customer.write',
  'order.read', 'order.write', 'invoice.read', 'invoice.write', 'audit.read', 'recovery.create', 'recovery.execute',
  'integrity.read', 'integrity.manage'];
const product = (i, sku, name, price, cost, active = true, version = 0) => ({
  id: `bbbbbbbb-0000-4000-8000-00000000000${i}`, organizationId: ORG, sku, name, unitPrice: price, unitCost: cost,
  active, createdAt: now, updatedAt: now, version });
const products = [
  product(1, 'P100', 'Fixture widget', 500, 3000, true, 1),
  product(2, 'T-100', 'Fixture cotton tee', 900, 300),
  product(3, 'K-220', 'Fixture kettle 1.5 L', 2499, 1400),
  product(4, 'U1000', 'Fixture bundle (retired)', 10, 5, false),
];
const customer = { id: 'cccccccc-0000-4000-8000-000000000001', organizationId: ORG, name: 'Fixture Customer Co.',
  email: 'buyer@example.test', createdAt: now, updatedAt: now, version: 0 };
const lines = [
  { id: 'dddddddd-0000-4000-8000-000000000001', orderId: 'eeeeeeee-0000-4000-8000-000000000001', lineNo: 1,
    productId: products[0].id, description: 'P100 - Fixture widget', quantity: 3, unitPrice: 500, lineTotal: 1500, version: 0 },
  { id: 'dddddddd-0000-4000-8000-000000000002', orderId: 'eeeeeeee-0000-4000-8000-000000000001', lineNo: 2,
    productId: products[1].id, description: 'T-100 - Fixture cotton tee', quantity: 2, unitPrice: 900, lineTotal: 1800, version: 0 },
];
const order = { id: 'eeeeeeee-0000-4000-8000-000000000001', organizationId: ORG, orderNumber: 'SO-000001',
  customerId: customer.id, status: 'DRAFT', totalAmount: 3300, createdBy: 'u', placedAt: null, createdAt: now,
  updatedAt: now, version: 2, lines };
const invoice = { id: 'ffffffff-0000-4000-8000-000000000001', organizationId: ORG, invoiceNumber: 'INV-000001',
  orderId: order.id, status: 'ISSUED', totalAmount: 3300, issuedAt: now, createdAt: now, updatedAt: now, version: 1,
  lines: lines.map((l, i) => ({ id: `99999999-0000-4000-8000-00000000000${i}`, invoiceId: 'ffffffff-0000-4000-8000-000000000001',
    lineNo: l.lineNo, orderLineId: l.id, description: l.description, quantity: l.quantity, unitPrice: l.unitPrice, lineTotal: l.lineTotal })) };
const page = (items) => ({ items, page: { number: 0, size: 20, totalItems: items.length, totalPages: 1 } });

// Audit fixtures: the faulty-price story (product v1 at 500 instead of 5000 -> line 1 -> order total).
const P = products[0].id;
const ev = (i, over) => ({ id: `a000000${i}-0000-4000-8000-000000000000`, chainIndex: i, txnId: `7000000${i}-0000-4000-8000-000000000000`,
  actorKind: 'USER', actorUserId: 'u', actorName: 'Kaavya (fixture)', entityVersion: 0, operation: 'INSERT', reason: null,
  correlationId: `fixture-req-${i}`, occurredAt: `2026-11-05T10:0${i}:00Z`, hash: `${i}f`.repeat(32), before: null, ...over });
const events = [
  ev(1, { entityType: 'product', entityId: P, changedFields: ['active', 'name', 'sku', 'unit_cost', 'unit_price'],
    after: { sku: 'P100', name: 'Fixture widget', unit_price: 5000, unit_cost: 3000, active: true }, reason: 'product created' }),
  ev(2, { entityType: 'product', entityId: P, entityVersion: 1, operation: 'UPDATE', changedFields: ['unit_price'],
    before: { sku: 'P100', name: 'Fixture widget', unit_price: 5000, unit_cost: 3000, active: true },
    after: { sku: 'P100', name: 'Fixture widget', unit_price: 500, unit_cost: 3000, active: true }, reason: 'price edit' }),
  ev(3, { entityType: 'order_line', entityId: lines[0].id, changedFields: ['description', 'line_total', 'quantity', 'unit_price'],
    after: { order_id: order.id, line_no: 1, product_id: P, description: lines[0].description, quantity: 3, unit_price: 500, line_total: 1500 },
    txnId: '70000004-0000-4000-8000-000000000000' }),
  ev(4, { entityType: 'order', entityId: order.id, entityVersion: 1, operation: 'UPDATE', changedFields: ['line_count', 'total_amount'],
    before: { order_number: 'SO-000001', status: 'DRAFT', total_amount: 0, line_count: 0 },
    after: { order_number: 'SO-000001', status: 'DRAFT', total_amount: 1500, line_count: 1 }, reason: 'line 1 added' }),
  ev(5, { actorKind: 'SYSTEM', actorName: null, actorUserId: null, entityType: 'customer', entityId: customer.id,
    changedFields: ['email', 'name'], after: { name: 'Fixture Customer Co.', email: 'buyer@example.test' } }),
].reverse();
const edgeFromFaulty = { source: { entityType: 'product', entityId: P, version: 1 }, sourceField: 'unit_price',
  target: { entityType: 'order_line', entityId: lines[0].id, version: 0 }, targetField: 'unit_price',
  relation: 'DERIVED_VALUE', evidence: 'RECORDED_AT_WRITE', txnId: '70000004-0000-4000-8000-000000000000', recordedAt: now };


// ATLAS X fixtures (fake, labelled): lineage with confidence, conflicts, a simulation, integrity.
const lineageEdge = (depth, over) => ({ depth, operation: 'order.add_line', confidence: 'VERIFIED', sourceValue: '500',
  targetValue: '500', actorKind: 'USER', actorUserId: 'u', correlationId: 'fixture-req-4', ...edgeFromFaulty, ...over });
const lineage = { direction: 'downstream', maxDepth: 4, truncated: false, edges: [
  lineageEdge(1, {}),
  lineageEdge(2, { source: { entityType: 'order_line', entityId: lines[0].id, version: 0 }, sourceField: 'line_total',
    target: { entityType: 'order', entityId: order.id, version: 1 }, targetField: 'total_amount', relation: 'AGGREGATED',
    sourceValue: '1500', targetValue: '1500', confidence: 'VERIFIED' }),
  lineageEdge(3, { source: { entityType: 'order', entityId: order.id, version: 1 }, sourceField: 'total_amount',
    target: { entityType: 'invoice', entityId: 'ffffffff-0000-4000-8000-000000000001', version: 0 }, targetField: 'total_amount',
    operation: 'invoice.from_order', relation: 'COPIED', sourceValue: '1500', targetValue: '1500', confidence: 'DECLARED' }),
] };
const fconflict = (over) => ({ key: { entityType: 'order_line', entityId: lines[0].id }, field: 'unit_price', kind: 'NO_CONFLICT',
  taintedFrom: 0, taintedTo: 0, laterVersion: null, laterActorKind: null, laterFields: [], recommendation: 'CORRECT_AUTOMATICALLY',
  severity: 'LOW', explanation: 'Still holds the faulty value; the plan corrects it (fixture).', ...over });
const conflictsFixture = { incidentId: '12000000-0000-4000-8000-000000000001', planId: null, open: 1, conflicts: [
  { conflict: fconflict({}), review: null, open: false },
  { conflict: fconflict({ key: { entityType: 'order', entityId: order.id }, field: 'delivery_note', kind: 'DIFFERENT_FIELD_UPDATE',
    laterVersion: 2, laterActorKind: 'USER', laterFields: ['delivery_note'], recommendation: 'PRESERVE_LATER_CHANGE',
    explanation: 'A person changed delivery_note in v2; field-level repair keeps it (fixture).' }), review: null, open: false },
  { conflict: fconflict({ key: { entityType: 'order_line', entityId: 'dddddddd-0000-4000-8000-000000000009' }, kind: 'SAME_FIELD_CONFLICT',
    laterVersion: 1, laterActorKind: 'USER', recommendation: 'HUMAN_REVIEW', severity: 'HIGH',
    explanation: 'A person set unit_price to 48000 in v1, after the fault (fixture).' }), review: null, open: true },
] };
const srow = (strategy, letter, over = {}) => ({ strategy, letter, applicable: true, notApplicableReason: null, recordsInspected: 5,
  recordsModified: 4, recordsDeleted: 0, faultyRecords: 4, repaired: 4, collateralModified: 0, legitimateOverwritten: 0,
  conflictsSurfaced: 1, conflictsIgnored: 0, invariantViolations: 0, fieldWrites: 6, compensations: 1, manualSteps: 1,
  simulationMicros: 850, violations: [], notes: [], ...over });
const cand = (strategy, feasible, rank, total, broken = []) => ({ strategy, feasible, rank,
  score: { total, collateral: 0, unresolved: 0, risk: 0, cost: 0 },
  constraints: ['APPLICABLE', 'INVARIANTS_HOLD', 'NO_COLLATERAL', 'PRESERVES_LATER_UPDATES', 'NO_DELETIONS', 'VERSION_CHECKED_WRITES',
    'CONFLICTS_GO_TO_PEOPLE', 'AUTHORIZED_EXECUTION'].map((code) => ({ code, description: '', passed: !broken.includes(code),
    detail: broken.includes(code) ? 'fixture: broken' : '' })) });
const simRun = { id: '15000000-0000-4000-8000-000000000001', incidentId: '12000000-0000-4000-8000-000000000001', status: 'COMPLETED',
  snapshotChainIndex: 18, fingerprint: 'sha256:fixture', records: 5, versions: 11, recommendedStrategy: 'PROVENANCE_CONFLICT_AWARE',
  riskLevel: 'HIGH', failure: null, startedBy: 'u', startedByName: 'Kaavya (fixture)', startedAt: now, finishedAt: now, durationMs: 24,
  assessment: { recommended: 'PROVENANCE_CONFLICT_AWARE', risk: 'HIGH', summary: 'Fixture: F meets every hard constraint.',
    weights: { collateral: 10, unresolved: 10, risk: 2, cost: 0.1 },
    candidates: [cand('FULL_RESTORE', false, 6, 0, ['NO_COLLATERAL', 'PRESERVES_LATER_UPDATES', 'CONFLICTS_GO_TO_PEOPLE']),
      cand('NAIVE_FK_ROW', false, 5, 0, ['NO_COLLATERAL', 'PRESERVES_LATER_UPDATES']), cand('VERSION_ROW_RESTORE', false, 4, 0, ['PRESERVES_LATER_UPDATES']),
      cand('FIELD_LEVEL_FK', false, 3, 0, ['NO_COLLATERAL']), cand('PROVENANCE_CONFLICT_BLIND', false, 2, 0, ['CONFLICTS_GO_TO_PEOPLE', 'INVARIANTS_HOLD']),
      cand('PROVENANCE_CONFLICT_AWARE', true, 1, 16.6)],
    riskFactors: [
      { factor: 'FINANCIAL_IMPACT', level: 'HIGH', value: '135000', explanation: 'Fixture: between 1,00,000 and 10,00,000.' },
      { factor: 'CONFLICTS', level: 'MEDIUM', value: '1', explanation: 'Fixture: one field needs a decision.' },
      { factor: 'REVERSIBILITY', level: 'HIGH', value: 'issued invoice', explanation: 'Fixture: an issued invoice needs a credit note.' },
      { factor: 'PROVENANCE_COMPLETENESS', level: 'LOW', value: '0 gaps', explanation: 'Fixture: every required edge was recorded.' }] } };
const simView = { run: simRun, results: [
  srow('FULL_RESTORE', 'A', { recordsModified: 9, collateralModified: 5, legitimateOverwritten: 2, conflictsSurfaced: 0, conflictsIgnored: 1 }),
  srow('NAIVE_FK_ROW', 'B', { recordsModified: 7, collateralModified: 3, legitimateOverwritten: 1, conflictsSurfaced: 0, conflictsIgnored: 1 }),
  srow('VERSION_ROW_RESTORE', 'C', { legitimateOverwritten: 1, conflictsSurfaced: 0, conflictsIgnored: 1 }),
  srow('FIELD_LEVEL_FK', 'D', { recordsModified: 6, collateralModified: 2 }),
  srow('PROVENANCE_CONFLICT_BLIND', 'E', { conflictsSurfaced: 0, conflictsIgnored: 1, invariantViolations: 1 }),
  srow('PROVENANCE_CONFLICT_AWARE', 'F')] };
const integrityRule = { id: '16000000-0000-4000-8000-000000000001', code: 'ORDER_TOTALS', kind: 'ORDER_TOTAL_MATCHES_LINES',
  name: 'Order totals match their lines', severity: 'HIGH', enabled: true, config: {}, version: 0, createdBy: 'u', createdAt: now, updatedAt: now };
const integrityIncident = { id: '17000000-0000-4000-8000-000000000001', ruleId: integrityRule.id, ruleCode: 'ORDER_TOTALS',
  ruleName: integrityRule.name, ruleVersion: 0, subjectType: 'order', subjectId: order.id, severity: 'HIGH', status: 'OPEN',
  title: 'SO-000001 total differs from its lines (fixture)', detail: { stored: '1500.0000', computed: '15000.0000' }, occurrences: 3,
  firstSeenAt: now, lastSeenAt: now, closedAt: null, version: 0 };

// Recovery fixtures (fake): P100 typed as 50000 instead of 5000; one invoice already ISSUED.
const INC = '12000000-0000-4000-8000-000000000001';
const PLAN = '13000000-0000-4000-8000-000000000001';
const incident = { id: INC, incidentNumber: 'REC-000001', faultyChangeEventId: events[3].id, title: 'Faulty product change: Unit price',
  reason: 'P100 entered as 50000 instead of 5000 (fixture)', status: 'PLANNED', openedBy: 'u', openedAt: now, version: 1 };
const key = (entityType, entityId) => ({ entityType, entityId });
const steps = [
  { number: 1, action: 'RESTORE_FIELDS', key: key('product', P), expectedVersion: 1, changes: [{ field: 'unit_price', current: 50000, proposed: 5000 }],
    dependsOn: [], risk: 'LOW', reason: 'Restore the value(s) the faulty change replaced', amount: null },
  { number: 2, action: 'UPDATE_FIELDS', key: key('order_line', lines[0].id), expectedVersion: 0,
    changes: [{ field: 'line_total', current: 150000, proposed: 15000 }, { field: 'unit_price', current: 50000, proposed: 5000 }],
    dependsOn: [1], risk: 'MEDIUM', reason: 'Recompute from corrected inputs', amount: null },
  { number: 3, action: 'UPDATE_FIELDS', key: key('order', order.id), expectedVersion: 3, changes: [{ field: 'total_amount', current: 151800, proposed: 16800 }],
    dependsOn: [2], risk: 'MEDIUM', reason: 'Recompute from corrected inputs', amount: null },
  { number: 4, action: 'ISSUE_CREDIT_NOTE', key: key('invoice', invoice.id), expectedVersion: 1, changes: [],
    dependsOn: [2], risk: 'HIGH', reason: 'Issued invoice over-billed by 135000.0000: credit the difference', amount: 135000 },
  { number: 5, action: 'MANUAL_REVIEW', key: key('order_line', 'dddddddd-0000-4000-8000-000000000009'), expectedVersion: 1, changes: [{ field: 'unit_price', current: 48000, proposed: 5000 }],
    dependsOn: [1], risk: 'HIGH', reason: 'unit_price was changed by a person after the fault', amount: null },
];
const impactRecords = [
  { key: key('product', P), classification: 'ORIGIN', depth: 0, currentVersion: 1, taintedFields: ['unit_price'], path: [], reason: null },
  { key: key('order_line', lines[0].id), classification: 'DIRECTLY_AFFECTED', depth: 1, currentVersion: 0, taintedFields: ['line_total', 'unit_price'], path: [], reason: null },
  { key: key('order', order.id), classification: 'INDIRECTLY_AFFECTED', depth: 2, currentVersion: 3, taintedFields: ['total_amount'], path: [], reason: null },
  { key: key('invoice', invoice.id), classification: 'NON_REVERSIBLE', depth: 3, currentVersion: 1, taintedFields: ['total_amount'], path: [], reason: 'issued invoice: compensate' },
  { key: key('order_line', 'dddddddd-0000-4000-8000-000000000009'), classification: 'CONFLICTED', depth: 1, currentVersion: 1, taintedFields: ['unit_price'], path: [], reason: 'unit_price was changed by a person after the fault' },
  { key: key('order_line', lines[1].id), classification: 'NOT_REACHED_PROVENANCE_COMPLETE', depth: 0, currentVersion: 0, taintedFields: [], path: [], reason: 'Summed into the order; not reached from the faulty change, and every required input of this record within the analysed scope was recorded (fixture)' },
];
const planFixture = (status) => ({ id: PLAN, incidentId: INC, planNumber: 2, fingerprint: 'sha256:' + '3fa9c1'.repeat(10) + 'beef',
  risk: 'HIGH', requiresApproval: true, stepCount: 5, manualStepCount: 1, status, createdBy: 'someone-else', createdAt: now,
  impact: { counts: { ORIGIN: 1, DIRECTLY_AFFECTED: 1, INDIRECTLY_AFFECTED: 1, NON_REVERSIBLE: 1, CONFLICTED: 1, NOT_REACHED_PROVENANCE_COMPLETE: 1 }, traversedEdges: 9, records: impactRecords },
  plan: { steps, invariants: [] } });
const act = (stepNo, status, detail, ack) => ({ stepNo, status, detail, resultVersion: status === 'DONE' ? 2 : null, resultEntityId: null,
  executedBy: 'u', recordedAt: now, acknowledgedBy: ack ? 'u' : null, acknowledgedAt: ack ? now : null, acknowledgement: ack ?? null });
const recoveryState = {
  proposed: { plan: planFixture('PROPOSED'), steps: [], decisions: [], verifications: [] },
  approved: { plan: planFixture('APPROVED'), steps: [], verifications: [],
    decisions: [{ decision: 'APPROVED', fingerprint: 'x', comment: 'credit checked with finance (fixture)', decidedBy: 'a', decidedByName: 'Approver (fixture)', decidedAt: now }] },
  executed: { plan: planFixture('EXECUTED'),
    steps: [act(1, 'DONE', 'Wrote version 2'), act(2, 'DONE', 'Wrote version 1'), act(3, 'DONE', 'Wrote version 4'),
      act(4, 'DONE', 'Issued credit note for 135000.0000'), act(5, 'ACKNOWLEDGED', '', '48000 was a negotiated price; kept (fixture)')],
    decisions: [{ decision: 'APPROVED', fingerprint: 'x', comment: 'credit checked with finance (fixture)', decidedBy: 'a', decidedByName: 'Approver (fixture)', decidedAt: now }],
    verifications: [{ id: 'v1', outcome: 'PASSED', checkCount: 9, failedCount: 0, pendingManual: 0, checks: [], verifiedBy: 'u', verifiedAt: now }] },
  failed: { plan: planFixture('FAILED'),
    steps: [act(1, 'DONE', 'Wrote version 2'), act(2, 'FAILED', 'Changed since planning: expected version 0, found 1. Not overwritten.'),
      act(3, 'BLOCKED', 'Step 2 did not complete (FAILED)'), act(4, 'BLOCKED', 'Step 2 did not complete (FAILED)'),
      act(5, 'AWAITING_ACKNOWLEDGEMENT', 'unit_price was changed by a person after the fault')],
    decisions: [{ decision: 'APPROVED', fingerprint: 'x', comment: null, decidedBy: 'a', decidedByName: 'Approver (fixture)', decidedAt: now }],
    verifications: [{ id: 'v1', outcome: 'FAILED', checkCount: 9, failedCount: 2, pendingManual: 1, verifiedBy: 'u', verifiedAt: now, checks: [
      { kind: 'STEP_DONE', key: null, description: 'Step 2 (UPDATE_FIELDS) completed', passed: false, actual: 'FAILED: Changed since planning' },
      { kind: 'NOTHING_LEFT_TO_REPAIR', key: null, description: 'A fresh analysis of the current data finds no automatic repair left', passed: false, actual: '3 left, e.g. UPDATE_FIELDS order_line:dddddddd' }] }] },
};

function routes(signedIn, { conflictOnPatch = false, recovery = 'proposed', admin = false } = {}) {
  return async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    const method = route.request().method();
    const ok = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/v1/auth/refresh') return signedIn ? ok({ accessToken: 'fixture', tokenType: 'Bearer', expiresAt: now })
      : route.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"status":401}' });
    if (p === '/api/v1/auth/login') return route.fulfill({ status: 401, contentType: 'application/problem+json',
      body: JSON.stringify({ status: 401, title: 'Unauthorized', detail: 'Invalid email or password, or the account is temporarily locked.', correlationId: 'fixture-cid' }) });
    if (p === '/api/v1/users/me') return ok({ id: 'u', email: 'kaavya@example.test', displayName: 'Kaavya (fixture)', platformAdmin: admin });
    // ER explorer: the REAL schema of migrations V1-V15, read from PostgreSQL's catalog by the graph SQL harness
    // and classified by the Java code (e2e/fixtures/schema-v15.json; trigger lists omitted).
    if (p === '/api/v1/graph/schema') return admin ? ok({ flywayVersion: '15', sha256: 'f'.repeat(64), schema: SCHEMA_V15,
      latestSnapshot: snapshotV15, changedSinceSnapshot: false }) : route.fulfill({ status: 403, contentType: 'application/problem+json', body: '{"status":403}' });
    if (p === '/api/v1/graph/schema/versions') return ok([snapshotV15]);
    if (p === '/api/v1/graph/schema/exports') return ok({ auditEventId: 'fixture-audit' }, 201);
    if (p === '/api/v1/organizations') return ok([org]);
    if (p === '/api/v1/access') return ok({ organizationId: ORG, roles: ['ORGANIZATION_ADMIN'], permissions: perms });
    if (p === '/api/v1/products') return ok(page(products));
    if (p.startsWith('/api/v1/products/')) {
      if (method === 'PATCH' && conflictOnPatch) return route.fulfill({ status: 409, contentType: 'application/problem+json',
        body: JSON.stringify({ status: 409, title: 'Conflict', detail: 'The product was changed by someone else (now at version 2).' }) });
      return ok(products[0]);
    }
    if (p === '/api/v1/customers') return ok(page([customer]));
    if (p.startsWith('/api/v1/customers/')) return ok(customer);
    if (p === '/api/v1/orders') return ok(page([order, { ...order, id: 'eeeeeeee-0000-4000-8000-000000000002', orderNumber: 'SO-000002', status: 'CONFIRMED', placedAt: now }]));
    if (p.startsWith('/api/v1/orders/')) return ok(order);
    if (p === '/api/v1/invoices') return ok(page([invoice]));
    if (p.endsWith('/credit-notes')) return ok([{ id: 'cn1', invoiceId: invoice.id, creditNoteNumber: 'CN-000001', amount: 300,
      reason: 'Goodwill discount (fixture)', status: 'ISSUED', issuedAt: now, createdAt: now, version: 0 }]);
    if (p.startsWith('/api/v1/invoices/')) return ok(invoice);
    if (p === '/api/v1/audit/verify') return ok({ intact: true, firstBrokenIndex: null, eventCount: events.length });
    if (p === '/api/v1/audit/events') {
      const type = url.searchParams.get('entityType');
      const id = url.searchParams.get('entityId');
      return ok(page(events.filter((e) => (!type || e.entityType === type) && (!id || e.entityId === id))));
    }
    if (p.endsWith('/lineage')) {
      const downstream = url.searchParams.get('direction') !== 'upstream';
      return ok(downstream && p.includes(P) && url.searchParams.get('version') === '1' ? lineage
        : { ...lineage, direction: downstream ? 'downstream' : 'upstream', edges: [] });
    }
    if (p.startsWith('/api/v1/history/')) return ok([]);
    if (p.startsWith('/api/v1/integrity/')) {
      if (p === '/api/v1/integrity/rules') return ok([integrityRule]);
      if (p === '/api/v1/integrity/rule-kinds') return ok([{ kind: 'ORDER_TOTAL_MATCHES_LINES', defaultCode: 'ORDER_TOTALS', name: integrityRule.name,
        description: '', defaultSeverity: 'HIGH', defaultConfig: {} }, { kind: 'AUDIT_CHAIN_INTACT', defaultCode: 'AUDIT_CHAIN',
        name: 'Audit chain is intact', description: 'Recomputes the hash chain (fixture).', defaultSeverity: 'CRITICAL', defaultConfig: {} }]);
      if (p === '/api/v1/integrity/incidents') return ok(page([integrityIncident]));
      if (p === `/api/v1/integrity/incidents/${integrityIncident.id}`) return ok({ incident: integrityIncident, history: [
        { fromStatus: null, toStatus: 'OPEN', actorUserId: null, actorName: null, note: null, runId: 'r', occurredAt: now }] });
      if (p === '/api/v1/integrity/checks') return ok([{ id: 'r', trigger: 'SCHEDULED', startedBy: null, status: 'COMPLETED',
        rulesEvaluated: 1, findings: 1, opened: 1, cleared: 0, failure: null, startedAt: now, finishedAt: now }]);
    }
    if (p.startsWith('/api/v1/provenance/')) {
      const downstream = url.searchParams.get('direction') === 'downstream';
      return ok(downstream && p.includes(P) && url.searchParams.get('version') === '1' ? [edgeFromFaulty] : []);
    }
    if (p.startsWith('/api/v1/recovery/')) {
      const st = recoveryState[recovery];
      if (p === '/api/v1/recovery/incidents') return ok(page([{ ...incident, status: ({ executed: 'RESOLVED', failed: 'OPEN', approved: 'APPROVED' })[recovery] ?? 'PLANNED' },
        { ...incident, id: '12000000-0000-4000-8000-000000000002', incidentNumber: 'REC-000002', title: 'Faulty product change: Unit cost', status: 'RESOLVED' }]));
      if (p === `/api/v1/recovery/incidents/${INC}`) return ok({ ...incident, status: ({ executed: 'RESOLVED', failed: 'OPEN', approved: 'APPROVED' })[recovery] ?? 'PLANNED' });
      if (p === `/api/v1/recovery/incidents/${INC}/plans`) return ok([st.plan, { ...planFixture('SUPERSEDED'), id: 'old', planNumber: 1, stepCount: 4 }]);
      if (p === `/api/v1/recovery/plans/${PLAN}/execution`) return ok({ planId: PLAN, status: st.plan.status, steps: st.steps, decisions: st.decisions });
      if (p === `/api/v1/recovery/plans/${PLAN}/verifications`) return ok(st.verifications);
      if (p === `/api/v1/recovery/incidents/${INC}/conflicts`) return ok(conflictsFixture);
      if (p === `/api/v1/recovery/incidents/${INC}/simulations`) return ok([simRun]);
      if (p === `/api/v1/recovery/simulations/${simRun.id}`) return ok(simView);
      if (p === `/api/v1/recovery/plans/${PLAN}/assessment`) return ok({ planId: PLAN, planFingerprint: 'x', stale: false, staleReason: null, simulation: simRun });
    }
    if (p === '/api/v1/members') return ok(page([{ userId: 'u', email: 'kaavya@example.test', displayName: 'Kaavya (fixture)',
      status: 'ACTIVE', joinedAt: now, roles: ['ORGANIZATION_ADMIN'], version: 0 }]));
    return route.fulfill({ status: 404, contentType: 'application/problem+json', body: '{"status":404,"detail":"fixture: no route"}' });
  };
}

// ------------------------------------------------------------------ run
const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--port', String(PORT), '--strictPort'],
  { stdio: 'pipe' });
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('vite preview did not start')), 20000);
  server.stdout.on('data', (d) => { if (String(d).includes(String(PORT))) { clearTimeout(t); resolve(); } });
});

const browser = await chromium.launch();
const shots = [];
const exports = [];
const errors = [];
const a11y = [];
async function shoot(name, { path, signedIn = true, dark = false, width = 1280, height = 800, act, conflictOnPatch, recovery, admin = false } = {}) {
  const context = await browser.newContext({ viewport: { width, height }, colorScheme: dark ? 'dark' : 'light' });
  const pg = await context.newPage();
  pg.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  // Chromium logs every non-2xx response as a console error; the 401/409 here are deliberate.
  pg.on('console', (m) => {
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(`${name}: console ${m.text()}`);
  });
  // Serve every page with the production Content-Security-Policy, so a graph library that needs
  // inline styles or eval would fail here (CSP reports go to the console and count as errors).
  await pg.route(`${BASE}/**`, async (route) => {
    if (route.request().resourceType() !== 'document') return route.fallback();
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': CSP } });
  });
  pg.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) errors.push(`${name}: CSP ${m.text()}`); });
  await pg.route('**/api/v1/**', routes(signedIn, { conflictOnPatch, recovery, admin }));
  await pg.goto(BASE + path);
  await pg.waitForLoadState('networkidle');
  if (act) await act(pg);
  await pg.waitForTimeout(250);
  const file = `${OUT}/${name}.png`;
  await pg.screenshot({ path: file, fullPage: false });
  const overflow = await pg.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  shots.push({ name, file, horizontalOverflow: overflow });
  if (A11Y) {
    const result = await new AxeBuilder({ page: pg }).withTags(WCAG).analyze();
    a11y.push({ name, passes: result.passes.length, incomplete: result.incomplete.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => [n.target.join(" "), n.any.concat(n.all).map((c) => c.message).join("; ")]).slice(0, 3) })),
      violations: result.violations.map((v) => ({ id: v.id, impact: v.impact, help: v.help,
        nodes: v.nodes.map((n) => n.target.join(' ')).slice(0, 5) })) });
  }
  await context.close();
}

try {
  await shoot('01-sign-in-error', { path: '/sign-in', signedIn: false, act: async (pg) => {
    await pg.getByLabel('Email').fill('kaavya@example.test');
    await pg.getByLabel('Password', { exact: true }).fill('wrong-password-here');
    await pg.getByRole('button', { name: 'Sign in' }).click();
    await pg.getByRole('alert').waitFor();
  } });
  await shoot('02-catalogue', { path: '/products' });
  await shoot('03-product-conflict', { path: '/products', conflictOnPatch: true, act: async (pg) => {
    await pg.getByRole('button', { name: 'P100' }).click();
    await pg.getByLabel('Unit price').fill('5000');
    await pg.getByRole('button', { name: 'Save changes' }).click();
    await pg.getByText('Someone else changed this record.').waitFor();
  } });
  await shoot('04-order-detail', { path: `/orders/${order.id}` });
  await shoot('05-invoice-detail', { path: `/invoices/${invoice.id}` });
  await shoot('06-catalogue-dark', { path: '/products', dark: true });
  await shoot('07-orders-mobile', { path: '/orders', width: 390, height: 844 });
  await shoot('08-void-confirm-dark', { path: `/invoices/${invoice.id}`, dark: true, act: async (pg) => {
    await pg.getByRole('button', { name: 'Issue credit note' }).waitFor();
  } });
  await shoot('09-audit', { path: '/audit', act: async (pg) => {
    await pg.getByRole('button', { name: 'Verify integrity' }).click();
    await pg.getByRole('status').filter({ hasText: 'Chain intact' }).waitFor();
  } });
  await shoot('10-audit-diff', { path: '/audit', act: async (pg) => {
    await pg.getByRole('button', { name: /Product .* v1/ }).click();
    await pg.getByRole('dialog').waitFor();
  } });
  await shoot('11-history-faulty-version', { path: `/history/product/${P}?version=1`, act: async (pg) => {
    await pg.getByRole('link', { name: /Order line/ }).waitFor();
  } });
  await shoot('12-history-mobile', { path: `/history/product/${P}?version=1`, width: 390, height: 844, act: async (pg) => {
    await pg.getByRole('link', { name: /Order line/ }).waitFor();
  } });
  await shoot('13-recovery-list', { path: '/recovery' });
  await shoot('14-incident-proposed', { path: `/recovery/${INC}`, height: 1100, recovery: 'proposed' });
  await shoot('15-incident-executed-verified', { path: `/recovery/${INC}`, height: 1000, recovery: 'executed' });
  await shoot('16-incident-failed-dark', { path: `/recovery/${INC}`, height: 1100, dark: true, recovery: 'failed' });
  await shoot('17-incident-mobile', { path: `/recovery/${INC}`, width: 390, height: 844, recovery: 'failed' });
  await shoot('18-execute-confirm', { path: `/recovery/${INC}`, recovery: 'approved', act: async (pg) => {
    await pg.getByRole('button', { name: 'Execute plan' }).click();
    await pg.getByLabel('Type PLAN-2 to confirm').fill('PLAN-2');
  } });
  await shoot('19-audit-open-incident', { path: '/audit', act: async (pg) => {
    await pg.getByRole('button', { name: /Product .* v1/ }).click();
    await pg.getByRole('button', { name: 'Open recovery incident' }).click();
    await pg.getByLabel('What was wrong').fill('P100 entered as 500 instead of 5000');
  } });
  await shoot('21-incident-conflicts', { path: `/recovery/${INC}`, height: 1500, recovery: 'proposed', act: async (pg) => {
    await pg.getByRole('table', { name: 'Conflicts' }).scrollIntoViewIfNeeded();
  } });
  await shoot('22-incident-simulation', { path: `/recovery/${INC}`, height: 1600, width: 1440, recovery: 'proposed', act: async (pg) => {
    await pg.getByRole('tab', { name: 'Simulation' }).click();
    await pg.getByRole('table', { name: 'Strategy comparison' }).scrollIntoViewIfNeeded();
  } });
  await shoot('23-integrity', { path: '/integrity', height: 1000 });
  await shoot('24-integrity-finding-dark', { path: '/integrity', dark: true, act: async (pg) => {
    await pg.getByRole('button', { name: integrityIncident.title }).click();
    await pg.getByRole('dialog').waitFor();
  } });
  await shoot('25-lineage-table', { path: `/history/product/${P}?version=1`, height: 1300, act: async (pg) => {
    await pg.getByRole('button', { name: 'Table' }).click();
    await pg.getByRole('table', { name: 'Lineage edges' }).waitFor();
  } });
  await shoot('26-lineage-react-flow', { path: `/history/product/${P}?version=1`, height: 1100, act: async (pg) => {
    await pg.getByText('Layered layout (ELK).', { exact: false }).waitFor();
  } });
  await shoot('27-lineage-react-flow-dark', { path: `/history/product/${P}?version=1`, height: 1100, dark: true, act: async (pg) => {
    await pg.getByText('Layered layout (ELK).', { exact: false }).waitFor();
    await pg.locator('[data-key]').nth(1).click();
    await pg.getByRole('dialog').waitFor();
  } });
  // Export both formats in a real browser under the production CSP; the files must be real images.
  for (const kind of ['svg', 'png']) {
    await shoot(`28-lineage-export-${kind}`, { path: `/history/product/${P}?version=1`, height: 1100, act: async (pg) => {
      await pg.getByText('Layered layout (ELK).', { exact: false }).waitFor();
      const [download] = await Promise.all([pg.waitForEvent('download'), pg.getByRole('button', { name: kind.toUpperCase(), exact: true }).click()]);
      const path = `${OUT}/lineage-export.${kind}`;
      await download.saveAs(path);
      const head = readFileSync(path).subarray(0, 8);
      const ok = kind === 'png' ? head[0] === 0x89 && head.toString('latin1', 1, 4) === 'PNG' : readFileSync(path, 'utf8').includes('<svg');
      exports.push({ kind, file: download.suggestedFilename(), bytes: readFileSync(path).length, ok });
    } });
  }
  await shoot('29-schema-er-diagram', { path: '/graph/schema', admin: true, width: 1440, height: 1100, act: async (pg) => {
    await pg.locator('[data-table]').first().waitFor();
  } });
  await shoot('30-schema-er-focus-dark', { path: '/graph/schema', admin: true, dark: true, width: 1440, height: 1250, act: async (pg) => {
    await pg.locator('[data-table="orders"]').click();
    await pg.getByRole('checkbox', { name: /and its neighbours/ }).check();
    await pg.getByText(/^\d+ of 41 tables/).filter({ hasNotText: '41 of 41' }).waitFor();
    await pg.waitForTimeout(600);
  } });
  await shoot('31-schema-denied', { path: '/graph/schema' });
  for (const kind of ['svg', 'png']) {
    await shoot(`32-schema-export-${kind}`, { path: '/graph/schema', admin: true, width: 1440, height: 1100, act: async (pg) => {
      await pg.locator('[data-table]').first().waitFor();
      const [download] = await Promise.all([pg.waitForEvent('download'), pg.getByRole('button', { name: kind.toUpperCase(), exact: true }).click()]);
      const path = `${OUT}/schema-export.${kind}`;
      await download.saveAs(path);
      const head = readFileSync(path).subarray(0, 8);
      const ok = kind === 'png' ? head[0] === 0x89 && head.toString('latin1', 1, 4) === 'PNG' : readFileSync(path, 'utf8').includes('<svg');
      exports.push({ kind: `schema-${kind}`, file: download.suggestedFilename(), bytes: readFileSync(path).length, ok });
    } });
  }
  // Keyboard only: reach every sign-in control with Tab, submit with Enter.
  await shoot('20-sign-in-keyboard-only', { path: '/sign-in', signedIn: false, act: async (pg) => {
    const focused = async () => pg.evaluate(() => document.activeElement?.getAttribute('aria-label')
      ?? document.activeElement?.labels?.[0]?.textContent ?? document.activeElement?.textContent ?? '');
    const seen = [];
    for (let i = 0; i < 8 && !seen.some((t) => /email/i.test(t)); i++) { await pg.keyboard.press('Tab'); seen.push(await focused()); }
    if (!/email/i.test(seen.at(-1))) throw new Error(`Tab never reached the email field: ${JSON.stringify(seen)}`);
    await pg.keyboard.type('kaavya@example.test');
    await pg.keyboard.press('Tab');
    if (!/password/i.test(await focused())) throw new Error('Tab after email did not reach the password field');
    await pg.keyboard.type('wrong-password-here');
    await pg.keyboard.press('Enter');
    await pg.getByRole('alert').waitFor();
  } });
} finally {
  await browser.close();
  server.kill('SIGTERM');
}
const violations = a11y.reduce((n, r) => n + r.violations.length, 0);
console.log(JSON.stringify(A11Y ? { shots, errors, exports, a11y } : { shots, errors, exports }, null, 2));
if (A11Y) console.error(`axe: ${a11y.length} states checked, ${violations} violation(s)`);
process.exit(errors.length || shots.some((s) => s.horizontalOverflow) || violations ? 1 : 0);
