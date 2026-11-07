import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { FieldConflict, IncidentConflicts, SimulationView, StrategyResultRow } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { IncidentInsights } from './IncidentInsights';

const session = vi.hoisted(() => ({ perms: new Set<string>() }));
vi.mock('@/session/SessionProvider', () => ({
  Perm: { recoveryCreate: 'recovery.create' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: (p: string) => session.perms.has(p) }),
}));

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });

const conflict = (over: Partial<FieldConflict>): FieldConflict => ({
  key: { entityType: 'order_line', entityId: 'line-0001' }, field: 'unit_price', kind: 'NO_CONFLICT',
  taintedFrom: 0, taintedTo: 0, laterVersion: null, laterActorKind: null, laterFields: [],
  recommendation: 'CORRECT_AUTOMATICALLY', severity: 'LOW', explanation: 'Still holds the faulty value.', ...over,
});
const conflicts: IncidentConflicts = {
  incidentId: 'inc-1', planId: 'plan-1', open: 1,
  conflicts: [
    { conflict: conflict({}), review: null, open: false },
    { conflict: conflict({ key: { entityType: 'order_line', entityId: 'line-0002' }, kind: 'SAME_FIELD_CONFLICT',
      laterVersion: 1, laterActorKind: 'USER', recommendation: 'HUMAN_REVIEW', severity: 'HIGH',
      explanation: 'A person set unit_price again in v1.' }), review: null, open: true },
  ],
};

const row = (strategy: StrategyResultRow['strategy'], letter: string, over: Partial<StrategyResultRow> = {}): StrategyResultRow => ({
  strategy, letter, applicable: true, notApplicableReason: null, recordsInspected: 4, recordsModified: 3, recordsDeleted: 0,
  faultyRecords: 3, repaired: 3, collateralModified: 0, legitimateOverwritten: 0, conflictsSurfaced: 0, conflictsIgnored: 0,
  invariantViolations: 0, fieldWrites: 5, compensations: 1, manualSteps: 0, simulationMicros: 900, violations: [], notes: [], details: null, ...over,
});
const sim: SimulationView = {
  run: {
    id: 'sim-1', incidentId: 'inc-1', status: 'COMPLETED', snapshotChainIndex: 42, fingerprint: 'sha256:x', records: 4, versions: 9,
    recommendedStrategy: 'PROVENANCE_CONFLICT_AWARE', riskLevel: 'MEDIUM', failure: null, startedBy: 'u', startedByName: 'Asha',
    startedAt: '2026-11-06T10:00:00Z', finishedAt: '2026-11-06T10:00:01Z', durationMs: 31,
    assessment: {
      candidates: [
        { strategy: 'FULL_RESTORE', feasible: false, rank: 6, score: { total: 0, collateral: 0, unresolved: 0, risk: 0, cost: 0 },
          constraints: [{ code: 'NO_COLLATERAL', description: '', passed: false, detail: '2 records changed that were never faulty' }] },
        { strategy: 'PROVENANCE_CONFLICT_AWARE', feasible: true, rank: 1, score: { total: 4.5, collateral: 0, unresolved: 0, risk: 4, cost: 0.5 },
          constraints: [{ code: 'NO_COLLATERAL', description: '', passed: true, detail: '' }] },
      ],
      recommended: 'PROVENANCE_CONFLICT_AWARE', risk: 'MEDIUM',
      riskFactors: [{ factor: 'FINANCIAL_IMPACT', level: 'MEDIUM', value: '13500', explanation: 'Between 10,000 and 100,000.' }],
      weights: { collateral: 10, unresolved: 10, risk: 2, cost: 0.1 }, summary: 'F meets every constraint.',
    },
  },
  results: [
    row('FULL_RESTORE', 'A', { collateralModified: 2, legitimateOverwritten: 1 }),
    row('PROVENANCE_CONFLICT_AWARE', 'F'),
  ],
};

function serve({ simulated }: { simulated: boolean }) {
  let ran = simulated;
  return vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://x').pathname;
    const method = init?.method ?? 'GET';
    if (path === '/api/v1/recovery/incidents/inc-1/conflicts') return reply(conflicts);
    if (path === '/api/v1/recovery/incidents/inc-1/conflicts/reviews' && method === 'POST') return reply({}, 201);
    if (path === '/api/v1/recovery/incidents/inc-1/simulations' && method === 'POST') { ran = true; return reply(sim, 201); }
    if (path === '/api/v1/recovery/incidents/inc-1/simulations') return reply(ran ? [sim.run] : []);
    if (path === '/api/v1/recovery/simulations/sim-1') return reply(sim);
    if (path === '/api/v1/recovery/simulations/sim-1/strategies/FULL_RESTORE/records') {
      const category = new URL(url, 'http://x').searchParams.get('category');
      const items = [
        { seq: 0, entityType: 'order', entityId: 'ord-0009', action: 'DELETE', inspected: true, proposed: true, modified: true,
          preserved: false, conflicting: false, unresolved: false, fields: ['total_amount'], provenancePath: [], conflictKind: null },
        { seq: 1, entityType: 'order_line', entityId: 'line-0002', action: 'MODIFY', inspected: true, proposed: true, modified: true,
          preserved: false, conflicting: true, unresolved: true, fields: ['unit_price'], provenancePath: [], conflictKind: 'SAME_FIELD_CONFLICT' },
      ].filter((x) => category !== 'CONFLICTING' || x.conflicting);
      return reply({ strategy: 'FULL_RESTORE', category, storedMatching: items.length, partial: true, page: 0, size: 50, items,
        totals: { total: 9, inspected: 9, proposed: 7, modified: 7, preserved: 0, conflicting: 1, unresolved: 2,
          preservedLaterChanges: 0, unresolvedDependencies: 0, stored: 2, omitted: 7, truncated: true } });
    }
    if (path.startsWith('/api/v1/recovery/simulations/sim-1/comparisons/'))
      return reply({ strategyA: 'FULL_RESTORE', strategyB: 'PROVENANCE_CONFLICT_AWARE', onlyA: 5, onlyB: 0, both: 3, sameAction: 1,
        totalDifferences: 7, storedDifferences: 1, omitted: 6, truncated: true, sha256: 'f'.repeat(64), createdAt: 'x',
        differences: [{ entityType: 'order', entityId: 'ord-0009', actionA: 'DELETE', fieldsA: [], actionB: 'NONE', fieldsB: [] }] });
    if (path === '/api/v1/recovery/plans/plan-1/assessment')
      return reply({ planId: 'plan-1', planFingerprint: 'p', stale: true, staleReason: 'The data changed since this simulation.', simulation: sim.run });
    return reply({ status: 404, detail: 'no fixture' }, 404);
  });
}

describe('IncidentInsights', () => {
  beforeEach(() => { api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { vi.unstubAllGlobals(); session.perms = new Set(); });

  it('lists each field conflict and records a review with its decision and reason', async () => {
    session.perms = new Set(['recovery.create']);
    const f = serve({ simulated: false });
    vi.stubGlobal('fetch', f);
    renderWithProviders(<IncidentInsights incidentId="inc-1" planId="plan-1" />);
    expect(await screen.findByText(/1 conflict need/)).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Conflicts' });
    expect(within(table).getByText('Same field changed later')).toBeInTheDocument();
    expect(within(table).getByText('Corrected by the plan')).toBeInTheDocument();
    // Only the conflict that needs a person offers a review.
    expect(within(table).getAllByRole('button', { name: 'Review' })).toHaveLength(1);

    await userEvent.click(within(table).getByRole('button', { name: 'Review' }));
    const dialog = await screen.findByRole('dialog');
    const save = within(dialog).getByRole('button', { name: 'Record decision' });
    expect(save).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Why'), 'customer agreed the new price');
    await userEvent.click(save);
    const call = f.mock.calls.find(([u, i]) => String(u).endsWith('/conflicts/reviews') && i?.method === 'POST')!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({
      entityType: 'order_line', entityId: 'line-0002', field: 'unit_price', decision: 'KEEP_LATER_CHANGE',
      note: 'customer agreed the new price',
    });
  });

  it('hides review buttons from people who cannot create recovery plans', async () => {
    vi.stubGlobal('fetch', serve({ simulated: false }));
    renderWithProviders(<IncidentInsights incidentId="inc-1" planId="plan-1" />);
    await screen.findByRole('table', { name: 'Conflicts' });
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
  });

  it('runs a simulation and compares the strategies with the recommended one marked', async () => {
    session.perms = new Set(['recovery.create']);
    const f = serve({ simulated: false });
    vi.stubGlobal('fetch', f);
    renderWithProviders(<IncidentInsights incidentId="inc-1" planId="plan-1" />);
    await userEvent.click(await screen.findByRole('tab', { name: 'Simulation' }));
    expect(await screen.findByText('No simulation yet')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Run simulation' }));
    const table = await screen.findByRole('table', { name: 'Strategy comparison' });
    const rows = within(table).getAllByRole('row');
    expect(within(rows[1]!).getByText('A. Point-in-time restore')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('1 broken')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('Recommended')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('all met')).toBeInTheDocument();
    expect(screen.getByText('Risk MEDIUM')).toBeInTheDocument();
    expect(screen.getAllByRole('status')[0]).toHaveTextContent('The data changed since this simulation.');
    expect(f.mock.calls.some(([u, i]) => String(u).endsWith('/incidents/inc-1/simulations') && i?.method === 'POST')).toBe(true);

    // Exact counts from the complete record sets; the stored differences are labelled partial.
    const compare = screen.getByRole('region', { name: 'Compare two strategies' });
    expect(await within(compare).findByText(/Only A acts on 5 · only B acts on 0 · both act on 3/)).toBeInTheDocument();
    expect(within(compare).getByRole('note')).toHaveTextContent('Partial: 1 of 7 differences are stored');

    await userEvent.click(within(rows[1]!).getByRole('button', { name: 'A. Point-in-time restore' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('2 records changed that were never faulty')).toBeInTheDocument();
    expect(await within(dialog).findByRole('note')).toHaveTextContent('Partial: 2 of 9 records are stored (7 omitted');
    expect(within(dialog).getByText('the list below is not complete.', { exact: false })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Conflicting 1' }));
    expect(await within(dialog).findByText('Same field changed later')).toBeInTheDocument();
    expect(within(dialog).queryByText('Delete')).not.toBeInTheDocument();
    expect(f.mock.calls.some(([u]) => String(u).includes('category=CONFLICTING'))).toBe(true);
  });
});
