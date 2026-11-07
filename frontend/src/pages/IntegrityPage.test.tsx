import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { IntegrityIncident, IntegrityRule } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { IntegrityPage } from './IntegrityPage';

const session = vi.hoisted(() => ({ perms: new Set<string>() }));
vi.mock('@/session/SessionProvider', () => ({
  Perm: { integrityRead: 'integrity.read', integrityManage: 'integrity.manage' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: (p: string) => session.perms.has(p) }),
}));

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });

const rule: IntegrityRule = {
  id: 'rule-1', code: 'ORDER_TOTALS', kind: 'ORDER_TOTAL_MATCHES_LINES', name: 'Order totals match their lines',
  severity: 'HIGH', enabled: true, config: {}, version: 3, createdBy: 'u', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z',
};
const incident: IntegrityIncident = {
  id: 'ii-1', ruleId: 'rule-1', ruleCode: 'ORDER_TOTALS', ruleName: rule.name, ruleVersion: 3, subjectType: 'order',
  subjectId: 'ord-0001', severity: 'HIGH', status: 'OPEN', title: 'SO-000004 total differs from its lines',
  detail: { stored: '1500.00', computed: '15000.00' }, occurrences: 2, firstSeenAt: '2026-11-05T00:00:00Z',
  lastSeenAt: '2026-11-06T00:00:00Z', closedAt: null, version: 0,
};
const kinds = [
  { kind: 'ORDER_TOTAL_MATCHES_LINES', defaultCode: 'ORDER_TOTALS', name: rule.name, description: '', defaultSeverity: 'HIGH', defaultConfig: {} },
  { kind: 'AUDIT_CHAIN_INTACT', defaultCode: 'AUDIT_CHAIN', name: 'Audit chain is intact', description: 'Recomputes the hash chain.', defaultSeverity: 'CRITICAL', defaultConfig: {} },
];

function serve() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const u = new URL(url, 'http://x');
    const method = init?.method ?? 'GET';
    if (u.pathname === '/api/v1/integrity/incidents') {
      const items = u.searchParams.get('status') === 'RESOLVED' ? [] : [incident];
      return reply({ items, page: { number: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 } });
    }
    if (u.pathname === '/api/v1/integrity/incidents/ii-1') return reply({ incident, history: [
      { fromStatus: null, toStatus: 'OPEN', actorUserId: null, actorName: null, note: null, runId: 'run-1', occurredAt: '2026-11-05T00:00:00Z' }] });
    if (u.pathname === '/api/v1/integrity/incidents/ii-1/transitions') return reply({ incident: { ...incident, status: 'ACKNOWLEDGED' }, history: [] });
    if (u.pathname === '/api/v1/integrity/rules' && method === 'GET') return reply([rule]);
    if (u.pathname === '/api/v1/integrity/rules' && method === 'POST') return reply({ ...rule, id: 'rule-2' }, 201);
    if (u.pathname === '/api/v1/integrity/rules/rule-1' && method === 'PUT') return reply({ ...rule, enabled: false, version: 4 });
    if (u.pathname === '/api/v1/integrity/rule-kinds') return reply(kinds);
    if (u.pathname === '/api/v1/integrity/checks' && method === 'POST')
      return reply({ runId: 'run-2', status: 'COMPLETED', rulesEvaluated: 1, findings: 1, opened: 0, cleared: 0, failures: [] }, 201);
    if (u.pathname === '/api/v1/integrity/checks') return reply([]);
    return reply({ status: 404, detail: 'no fixture' }, 404);
  });
}

describe('IntegrityPage', () => {
  beforeEach(() => { api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { vi.unstubAllGlobals(); session.perms = new Set(); });

  it('shows open findings with a link to the record history, read-only without integrity.manage', async () => {
    session.perms = new Set(['integrity.read']);
    vi.stubGlobal('fetch', serve());
    renderWithProviders(<IntegrityPage />);
    const table = await screen.findByRole('table', { name: 'Findings' });
    expect(within(table).getByText(incident.title)).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: /Order/ })).toHaveAttribute('href', '/history/order/ord-0001');
    expect(await screen.findByRole('table', { name: 'Rules' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run checks now' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disable' })).not.toBeInTheDocument();
    expect(screen.getByText('No checks have run')).toBeInTheDocument();
  });

  it('runs checks, toggles a rule with its version, offers only unused kinds, and acknowledges with a note', async () => {
    session.perms = new Set(['integrity.read', 'integrity.manage']);
    const f = serve();
    vi.stubGlobal('fetch', f);
    renderWithProviders(<IntegrityPage />);
    await screen.findByRole('table', { name: 'Rules' });

    await userEvent.click(screen.getByRole('button', { name: 'Run checks now' }));
    expect(await screen.findByText(/1 rule evaluated: 1 finding/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));
    const put = f.mock.calls.find(([u, i]) => String(u).endsWith('/rules/rule-1') && i?.method === 'PUT')!;
    expect(new Headers(put[1]!.headers).get('If-Match')).toBe('"3"');
    expect(JSON.parse(String(put[1]!.body))).toMatchObject({ enabled: false, severity: 'HIGH' });

    const offered = await screen.findByRole('list', { name: 'Checks you can add' });
    expect(within(offered).getAllByRole('listitem')).toHaveLength(1);
    expect(within(offered).getByText('Audit chain is intact')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: incident.title }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/integrity check/)).toBeInTheDocument();
    const ack = within(dialog).getByRole('button', { name: 'Acknowledge' });
    expect(ack).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Note'), 'looking into it');
    await userEvent.click(ack);
    const post = f.mock.calls.find(([u]) => String(u).endsWith('/transitions'))!;
    expect(new Headers(post[1]!.headers).get('If-Match')).toBe('"0"');
    expect(JSON.parse(String(post[1]!.body))).toEqual({ status: 'ACKNOWLEDGED', note: 'looking into it' });
  });

  it('filters findings by status', async () => {
    session.perms = new Set(['integrity.read']);
    const f = serve();
    vi.stubGlobal('fetch', f);
    renderWithProviders(<IntegrityPage />);
    await screen.findByRole('table', { name: 'Findings' });
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'RESOLVED');
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument();
    expect(f.mock.calls.some(([u]) => String(u).includes('status=RESOLVED'))).toBe(true);
  });
});
