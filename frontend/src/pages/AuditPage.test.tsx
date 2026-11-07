import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { AuditEvent } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { AuditPage } from './AuditPage';

vi.mock('@/session/SessionProvider', () => ({
  Perm: { auditRead: 'audit.read', recoveryCreate: 'recovery.create' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: () => true }),
}));

const event = (over: Partial<AuditEvent>): AuditEvent => ({
  id: 'e2', chainIndex: 2, txnId: 'txn-22222222', actorKind: 'USER', actorUserId: 'u1', actorName: 'Kaavya',
  entityType: 'product', entityId: 'p1000000-0000', entityVersion: 1, operation: 'UPDATE',
  changedFields: ['unit_price'], before: { sku: 'P100', unit_price: 5000 }, after: { sku: 'P100', unit_price: 500 },
  reason: 'typo', correlationId: 'cid-1', occurredAt: '2026-11-05T10:00:00Z', hash: 'ab'.repeat(32), ...over,
});
const page = (items: AuditEvent[]) => ({ items, page: { number: 0, size: 25, totalItems: items.length, totalPages: 1 } });
const reply = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

describe('AuditPage', () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal('fetch', fetchMock); api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { fetchMock.mockReset(); vi.unstubAllGlobals(); });

  it('lists changes with actor names and opens a field-level diff', async () => {
    fetchMock.mockResolvedValue(reply(page([
      event({}),
      event({ id: 'e1', chainIndex: 1, actorKind: 'SYSTEM', actorName: null, actorUserId: null, operation: 'INSERT',
        entityVersion: 0, before: null, changedFields: ['sku', 'unit_price'], txnId: 'txn-11111111' }),
    ])));
    renderWithProviders(<AuditPage />);
    const rows = await screen.findAllByRole('row');
    expect(within(rows[1]!).getByText('Kaavya')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('System')).toBeInTheDocument();

    await userEvent.click(within(rows[1]!).getByRole('button', { name: /Product/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('5000')).toHaveClass('line-through');
    expect(within(dialog).getByText('500')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: /Record history/ })).toHaveAttribute('href', '/history/product/p1000000-0000?version=1');
  });

  it('filters to one transaction', async () => {
    fetchMock.mockResolvedValue(reply(page([event({})])));
    renderWithProviders(<AuditPage />);
    const rows = await screen.findAllByRole('row');
    await userEvent.click(within(rows[1]!).getByRole('button', { name: /Product/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Everything in this transaction' }));
    await screen.findByText(/Showing one transaction/);
    const lastUrl = String(fetchMock.mock.calls.at(-1)![0]);
    expect(lastUrl).toContain('txnId=txn-22222222');
  });

  it('reports a broken chain loudly, with the first bad event', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/audit/verify') ? reply({ intact: false, firstBrokenIndex: 2, eventCount: 7 }) : reply(page([event({})])));
    renderWithProviders(<AuditPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('the chain breaks at event #2');
  });

  it('confirms an intact chain with the event count', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/audit/verify') ? reply({ intact: true, firstBrokenIndex: null, eventCount: 7 }) : reply(page([event({})])));
    renderWithProviders(<AuditPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }));
    expect(await screen.findByRole('status')).toHaveTextContent('all 7 events verify');
  });

  it('opens a recovery incident from an UPDATE and goes to it', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) =>
      String(url).endsWith('/recovery/incidents') && init?.method === 'POST'
        ? reply({ id: 'inc-9', incidentNumber: 'REC-000009' })
        : reply(page([event({})])));
    renderWithProviders(<AuditPage />);
    const rows = await screen.findAllByRole('row');
    await userEvent.click(within(rows[1]!).getByRole('button', { name: /Product/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open recovery incident' }));
    const create = screen.getByRole('button', { name: 'Open incident' });
    expect(create).toBeDisabled();
    await userEvent.type(screen.getByLabelText('What was wrong'), 'P100 entered as 500');
    await userEvent.click(create);
    const call = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/recovery/incidents') && i?.method === 'POST')!;
    expect(JSON.parse(String(call[1].body))).toEqual({ changeEventId: 'e2', title: 'Faulty product change: Unit price',
      reason: 'P100 entered as 500' });
  });
});
