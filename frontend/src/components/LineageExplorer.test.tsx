import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { Confidence, LineageEdge } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { LineageExplorer, MAX_RENDERED_NODES } from './LineageExplorer';

vi.mock('@/session/SessionProvider', () => ({
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: () => true }),
}));

const ref = (entityType: string, entityId: string, version: number) => ({ entityType, entityId, version });
const e = (depth: number, s: ReturnType<typeof ref>, sf: string, t: ReturnType<typeof ref>, tf: string,
  confidence: Confidence = 'VERIFIED'): LineageEdge => ({
  depth, source: s, sourceField: sf, target: t, targetField: tf, relation: 'DERIVED_VALUE', operation: 'order.add_line',
  evidence: 'RECORDED_AT_WRITE', confidence, sourceValue: '1', targetValue: '1', txnId: 't', actorKind: 'USER',
  actorUserId: null, correlationId: null, recordedAt: '2026-11-06T10:00:00Z',
});
const P = ref('product', 'p0000000-0000-4000-8000-000000000001', 1);
const L = ref('order_line', 'l0000000-0000-4000-8000-000000000002', 0);
const O = ref('order', 'o0000000-0000-4000-8000-000000000003', 1);
const I = ref('invoice', 'i0000000-0000-4000-8000-000000000004', 0);
const base = [e(1, P, 'unit_price', L, 'unit_price'), e(2, L, 'line_total', O, 'total_amount', 'DECLARED')];
const lineage = (edges: LineageEdge[]) => ({ direction: 'downstream', maxDepth: 4, truncated: false, edges });
const reply = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

function serve(rootEdges: LineageEdge[]) {
  return vi.fn(async (url: string) => {
    const u = new URL(url, 'http://x');
    if (u.pathname === `/api/v1/provenance/product/${P.entityId}/lineage`) return reply(lineage(rootEdges));
    // Expanding the order: one more recorded hop, numbered from the order (depth 1).
    if (u.pathname === `/api/v1/provenance/order/${O.entityId}/lineage`)
      return reply(lineage([e(1, O, 'total_amount', I, 'total_amount')]));
    return reply(lineage([]));
  });
}

const nodesIn = (region: HTMLElement) => within(region).getAllByRole('button').filter((b) => b.hasAttribute('data-key'));

describe('LineageExplorer (React Flow)', () => {
  beforeEach(() => { api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => vi.unstubAllGlobals());

  it('lays the graph out with ELK, with a minimap, and keeps graph and table selection in sync', async () => {
    vi.stubGlobal('fetch', serve(base));
    renderWithProviders(<LineageExplorer entityType="product" entityId={P.entityId} version={1} />);
    const graph = await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    expect(await screen.findByText(/Layered layout \(ELK\)/)).toBeInTheDocument();
    expect(nodesIn(graph)).toHaveLength(3);
    expect(graph.querySelector('.react-flow__minimap')).not.toBeNull();
    // Edge labels carry the field mapping; confidence is the line style (and the edge's accessible name).
    expect(await within(graph).findByText(/Line total → Total amount/i)).toBeInTheDocument();

    // Select the order line in the graph: its rows are marked in the table.
    await userEvent.click(nodesIn(graph)[1]!);
    expect(nodesIn(graph)[1]).toHaveAttribute('aria-pressed', 'true');
    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    const table = screen.getByRole('table', { name: 'Lineage edges' });
    expect(within(table).getAllByRole('row').filter((r) => r.getAttribute('aria-current') === 'true')).toHaveLength(2);

    // Select the order from the table: back in the graph, it is the selected node.
    await userEvent.click(within(table).getAllByRole('button', { name: /^Select Order o0000000 v1 in the graph$/ })[0]!);
    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    const again = await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    const order = nodesIn(again).find((n) => n.getAttribute('data-key') === `order:${O.entityId}@1`)!;
    expect(order).toHaveAttribute('aria-pressed', 'true');
  });

  it('expands one record progressively without reloading the rest, and can undo it', async () => {
    const f = serve(base);
    vi.stubGlobal('fetch', f);
    renderWithProviders(<LineageExplorer entityType="product" entityId={P.entityId} version={1} />);
    const graph = await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    await screen.findByText(/Layered layout/);
    const order = nodesIn(graph).find((n) => n.getAttribute('data-key') === `order:${O.entityId}@1`)!;
    await userEvent.click(order);
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: /Expand 2 hops onward/ }));
    expect(f.mock.calls.some(([u]) => String(u).includes(`/order/${O.entityId}/lineage`) && String(u).includes('depth=2'))).toBe(true);
    expect(await screen.findByText(/Expanded from 1 record \(1 edges added\)/)).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    const rows = within(screen.getByRole('table', { name: 'Lineage edges' })).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    // The new edge sits at hop 3: one hop beyond the order, which was 2 hops from the product.
    expect(within(rows[2]!).getAllByRole('cell')[0]).toHaveTextContent('3');
    await userEvent.click(screen.getByRole('button', { name: 'Undo expansions' }));
    expect(within(screen.getByRole('table', { name: 'Lineage edges' })).getAllByRole('row').slice(1)).toHaveLength(2);
  });

  it(`draws at most ${MAX_RENDERED_NODES} records and says how many are left out`, async () => {
    const many = Array.from({ length: MAX_RENDERED_NODES + 20 }, (_, i) =>
      e(1, P, 'unit_price', ref('order_line', `l${String(i).padStart(7, '0')}-0000-4000-8000-000000000000`, 0), 'unit_price'));
    vi.stubGlobal('fetch', serve(many));
    renderWithProviders(<LineageExplorer entityType="product" entityId={P.entityId} version={1} />);
    expect(await screen.findByText(`Showing ${MAX_RENDERED_NODES} of ${MAX_RENDERED_NODES + 21} records`, { exact: false })).toBeInTheDocument();
    const graph = await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    await screen.findByText(/layout/i);
    expect(nodesIn(graph)).toHaveLength(MAX_RENDERED_NODES);
    // Searching narrows what is drawn when only matching edges are kept.
    await userEvent.type(screen.getByLabelText('Find a record'), 'l0000003');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Only edges touching matches' }));
    await screen.findByRole('region', { name: 'Lineage graph (scrolls)' });
    expect(screen.queryByText(/Showing \d+ of/)).not.toBeInTheDocument();
  });
});
