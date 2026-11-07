import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { AuditEvent, ProvenanceEdge } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { HistoryPage } from './HistoryPage';

vi.mock('@/session/SessionProvider', () => ({
  Perm: { auditRead: 'audit.read' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: () => true }),
}));

const P = 'p1111111-aaaa-4000-8000-000000000001';
const L = 'l2222222-bbbb-4000-8000-000000000002';
const version = (v: number, price: number, op: AuditEvent['operation']): AuditEvent => ({
  id: `e${v}`, chainIndex: v + 1, txnId: `t${v}`, actorKind: 'USER', actorUserId: 'u', actorName: 'Kaavya',
  entityType: 'product', entityId: P, entityVersion: v, operation: op, changedFields: op === 'INSERT' ? ['sku', 'unit_price'] : ['unit_price'],
  before: op === 'INSERT' ? null : { sku: 'P100', unit_price: 5000 }, after: { sku: 'P100', unit_price: price },
  reason: null, correlationId: null, occurredAt: '2026-11-05T10:00:00Z', hash: '00',
});
const edge: ProvenanceEdge = {
  source: { entityType: 'product', entityId: P, version: 1 }, sourceField: 'unit_price',
  target: { entityType: 'order_line', entityId: L, version: 0 }, targetField: 'unit_price',
  relation: 'DERIVED_VALUE', evidence: 'RECORDED_AT_WRITE', txnId: 't9', recordedAt: '2026-11-05T10:05:00Z',
};
const O = 'o3333333-cccc-4000-8000-000000000003';
const lineEdge = (depth: number, conf: 'VERIFIED' | 'DECLARED') => ({
  depth, sourceField: depth === 1 ? 'unit_price' : 'line_total', targetField: depth === 1 ? 'unit_price' : 'total_amount',
  source: depth === 1 ? { entityType: 'product', entityId: P, version: 1 } : { entityType: 'order_line', entityId: L, version: 0 },
  target: depth === 1 ? { entityType: 'order_line', entityId: L, version: 0 } : { entityType: 'order', entityId: O, version: 1 },
  relation: depth === 1 ? 'DERIVED_VALUE' : 'AGGREGATED_INTO', operation: 'order.add_line', evidence: 'RECORDED_AT_WRITE',
  confidence: conf, sourceValue: '500', targetValue: depth === 1 ? '500' : '1500', txnId: 't', actorKind: 'USER',
  actorUserId: null, correlationId: null, recordedAt: '2026-11-05T10:05:00Z',
});
const lineage = { direction: 'downstream', maxDepth: 4, truncated: false, edges: [lineEdge(1, 'VERIFIED'), lineEdge(2, 'DECLARED')] };
const reply = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

describe('HistoryPage', () => {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.includes('/audit/events')) {
      return reply({ items: [version(1, 500, 'UPDATE'), version(0, 5000, 'INSERT')], page: { number: 0, size: 100, totalItems: 2, totalPages: 1 } });
    }
    if (url.includes('/lineage')) {
      if (url.includes('direction=downstream') && url.includes('version=1')) return reply(lineage);
      return reply({ direction: 'upstream', maxDepth: 4, truncated: false, edges: [] });
    }
    if (url.includes('/history/')) return reply([]);
    if (url.includes('direction=downstream') && url.includes('version=1')) return reply([edge]);
    return reply([]);
  });
  beforeEach(() => { vi.stubGlobal('fetch', fetchMock); api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { fetchMock.mockClear(); vi.unstubAllGlobals(); });

  const renderAt = (path: string) =>
    renderWithProviders(<Routes><Route path="/history/:entityType/:entityId" element={<HistoryPage />} /></Routes>, { route: path });

  it('lists versions oldest first and shows what the faulty version fed into, linking the exact version', async () => {
    renderAt(`/history/product/${P}?version=1`);
    const versions = await screen.findByRole('list', { name: 'Versions' });
    const items = within(versions).getAllByRole('button');
    expect(items.map((b) => b.textContent?.slice(0, 2))).toEqual(['v0', 'v1']);
    expect(items[1]).toHaveAttribute('aria-current', 'true');

    const link = await screen.findByRole('link', { name: /Order line/ });
    expect(link).toHaveAttribute('href', `/history/order_line/${L}?version=0`);
    expect(screen.getByText('Its unit price was derived from this unit price')).toBeInTheDocument();
    expect(screen.getByText("No recorded inputs: this version's values were entered, not derived.")).toBeInTheDocument();
  });

  it('switches version and asks for that version\'s lineage', async () => {
    renderAt(`/history/product/${P}?version=1`);
    const versions = await screen.findByRole('list', { name: 'Versions' });
    await userEvent.click(within(versions).getAllByRole('button')[0]!);
    expect(await screen.findByText('Nothing has been derived from this version.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('direction=downstream') && String(u).includes('version=0'))).toBe(true);
  });

  it('draws multi-hop lineage from recorded edges only, with confidence, details and a table view', async () => {
    renderAt(`/history/product/${P}?version=1`);
    const graph = await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    const nodes = within(graph).getAllByRole('button');
    expect(nodes.map((n) => n.textContent)).toEqual([
      expect.stringMatching(/^Product v1 .* \(this record\)$/),
      expect.stringMatching(/^Order line v0 \S+$/),
      expect.stringMatching(/^Order v1 \S+$/),
    ]);
    await userEvent.click(nodes[1]!);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Computed from')).toBeInTheDocument();
    expect(within(dialog).getAllByText('Verified').length).toBeGreaterThan(0);
    expect(within(dialog).getByText('Declared')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    // Hiding DECLARED edges removes the order total hop; the table view says the same in text.
    await userEvent.click(screen.getByRole('button', { name: /Declared/, pressed: true }));
    expect(within(screen.getByRole('region', { name: 'Lineage graph (scrolls)' })).getAllByRole('button')).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    const table = screen.getByRole('table', { name: 'Lineage edges' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getByText('order.add_line')).toBeInTheDocument();
  });
});

