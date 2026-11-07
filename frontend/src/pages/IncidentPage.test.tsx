import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { ActionRow, Plan, VerificationRow } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { IncidentPage } from './IncidentPage';

const session = vi.hoisted(() => ({ userId: 'approver', perms: new Set<string>() }));
vi.mock('@/session/SessionProvider', () => ({
  Perm: { auditRead: 'audit.read', recoveryCreate: 'recovery.create', recoveryApprove: 'recovery.approve',
    recoveryExecute: 'recovery.execute' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, me: { id: session.userId },
    can: (p: string) => session.perms.has(p) }),
}));

const incident = { id: 'inc-1', incidentNumber: 'REC-000001', faultyChangeEventId: 'ev-1', title: 'Mistyped price',
  reason: 'P100 entered as 50000', status: 'PLANNED', openedBy: 'author', openedAt: '2026-11-05T10:00:00Z', version: 1 };
const plan = (over: Partial<Plan> = {}): Plan => ({
  id: 'plan-1', incidentId: 'inc-1', planNumber: 1, fingerprint: 'sha256:' + 'ab'.repeat(32), risk: 'HIGH',
  requiresApproval: true, stepCount: 3, manualStepCount: 1, status: 'PROPOSED',
  impact: { counts: { ORIGIN: 1, DIRECTLY_AFFECTED: 1 }, traversedEdges: 4, records: [] },
  plan: { invariants: [], steps: [
    { number: 1, action: 'RESTORE_FIELDS', key: { entityType: 'product', entityId: 'prod-0001' }, expectedVersion: 1,
      changes: [{ field: 'unit_price', current: 50000, proposed: 5000 }], dependsOn: [], risk: 'LOW', reason: 'Restore', amount: null },
    { number: 2, action: 'ISSUE_CREDIT_NOTE', key: { entityType: 'invoice', entityId: 'inv-0001' }, expectedVersion: 1,
      changes: [], dependsOn: [1], risk: 'HIGH', reason: 'Over-billed', amount: 135000 },
    { number: 3, action: 'MANUAL_REVIEW', key: { entityType: 'order_line', entityId: 'line-0001' }, expectedVersion: 1,
      changes: [], dependsOn: [1], risk: 'HIGH', reason: 'Edited by a person since', amount: null },
  ] },
  createdBy: 'author', createdAt: '2026-11-05T10:05:00Z', ...over,
});
const action = (stepNo: number, status: ActionRow['status'], detail = 'ok'): ActionRow => ({
  stepNo, status, detail, resultVersion: null, resultEntityId: null, executedBy: 'admin', recordedAt: 'x',
  acknowledgedBy: null, acknowledgedAt: null, acknowledgement: null });
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });

function serve(p: Plan, steps: ActionRow[] = [], verifications: VerificationRow[] = []) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://x').pathname;
    const method = init?.method ?? 'GET';
    if (path === '/api/v1/recovery/incidents/inc-1') return reply(incident);
    if (path === '/api/v1/recovery/incidents/inc-1/plans') return reply([p]);
    if (path === '/api/v1/recovery/plans/plan-1/execution' && method === 'GET')
      return reply({ planId: 'plan-1', status: p.status, steps, decisions: [] });
    if (path === '/api/v1/recovery/plans/plan-1/verifications' && method === 'GET') return reply(verifications);
    if (path === '/api/v1/recovery/plans/plan-1/approval') return reply({ planId: 'plan-1', userId: 'approver', approved: true, fingerprint: p.fingerprint, comment: null });
    return reply({ status: 404, detail: 'no fixture' }, 404);
  });
}

const show = () => renderWithProviders(
  <Routes><Route path="/recovery/:id" element={<IncidentPage />} /></Routes>, { route: '/recovery/inc-1' });

describe('IncidentPage', () => {
  let fetchMock: ReturnType<typeof serve>;
  beforeEach(() => { api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { vi.unstubAllGlobals(); session.perms = new Set(); session.userId = 'approver'; });
  const use = (f: ReturnType<typeof serve>) => { fetchMock = f; vi.stubGlobal('fetch', f); };

  it('shows each step with its before and after values and lets another person approve the exact fingerprint', async () => {
    session.perms = new Set(['audit.read', 'recovery.approve']);
    use(serve(plan()));
    show();
    expect(await screen.findByText('Plan 1')).toBeInTheDocument();
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]!).getByText('50000')).toHaveClass('line-through');
    expect(within(rows[1]!).getByText('5000')).toBeInTheDocument();
    expect(within(rows[2]!).getByText(/1,35,000/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Execute plan' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/approval'))!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ fingerprint: plan().fingerprint });
  });

  it('does not offer the author approval and says why', async () => {
    session.perms = new Set(['audit.read', 'recovery.approve', 'recovery.create']);
    session.userId = 'author';
    use(serve(plan()));
    show();
    expect(await screen.findByText(/someone else must approve it/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('asks for a typed confirmation before executing', async () => {
    session.perms = new Set(['audit.read', 'recovery.execute']);
    use(serve(plan({ status: 'APPROVED' })));
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Execute plan' }));
    const dialog = await screen.findByRole('dialog');
    const go = within(dialog).getByRole('button', { name: 'Execute' });
    expect(go).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Type PLAN-1 to confirm'), 'PLAN-1');
    expect(go).toBeEnabled();
  });

  it('shows outcomes, offers acknowledgement of manual steps, and lists only the failed checks', async () => {
    session.perms = new Set(['audit.read', 'recovery.execute']);
    use(serve(plan({ status: 'FAILED' }),
      [action(1, 'DONE', 'Wrote version 2'), action(2, 'FAILED', 'Changed since planning: expected version 1, found 2. Not overwritten.'),
        action(3, 'AWAITING_ACKNOWLEDGEMENT', 'Edited by a person since')],
      [{ id: 'v1', outcome: 'FAILED', checkCount: 5, failedCount: 1, pendingManual: 1, verifiedBy: 'admin', verifiedAt: '2026-11-05T11:00:00Z',
        checks: [{ kind: 'STEP_DONE', key: null, description: 'Step 2 (ISSUE_CREDIT_NOTE) completed', passed: false, actual: 'FAILED: changed' },
          { kind: 'SCOPE', key: null, description: 'Only plan records', passed: true, actual: '1 written' }] }]));
    show();
    expect(await screen.findByText(/Not overwritten/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record what was done' })).toBeInTheDocument();
    expect(await screen.findByText('4 of 5 checks passed', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('STEP_DONE')).toBeInTheDocument();
    expect(screen.queryByText('Only plan records')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });
});
