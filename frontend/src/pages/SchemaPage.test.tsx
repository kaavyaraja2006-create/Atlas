import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { LiveSchema } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { SchemaPage } from './SchemaPage';

const session = vi.hoisted(() => ({ admin: false }));
vi.mock('@/session/SessionProvider', () => ({
  Perm: {},
  useSession: () => ({ current: { id: 'org-1' }, me: { id: 'u', platformAdmin: session.admin }, can: () => false }),
}));

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });

const live: LiveSchema = {
  flywayVersion: '14', sha256: 'c'.repeat(64), changedSinceSnapshot: false,
  latestSnapshot: { id: 'snap-1', flywayVersion: '14', sha256: 'c'.repeat(64), tableCount: 2, relationshipCount: 2, capturedAt: '2026-11-06T10:00:00Z' },
  schema: {
    tables: [
      { name: 'invoices', kind: 'TABLE', columns: [{ name: 'id', position: 1, type: 'uuid', nullable: false, defaultValue: null }],
        constraints: [{ name: 'invoices_pkey', kind: 'PRIMARY_KEY', columns: ['id'], definition: 'PRIMARY KEY (id)', references: null, referencedColumns: [], deferrable: false, validated: true }],
        indexes: [{ name: 'ux_invoices_active_per_order', columns: ['organization_id', 'order_id'], unique: true, primary: false, predicate: "((status)::text <> 'VOID'::text)", definition: 'CREATE UNIQUE INDEX …' }],
        triggers: [] },
      { name: 'field_changes', kind: 'VIEW', columns: [], constraints: [], indexes: [], triggers: [] },
      ...['orders', 'roles', 'permissions'].map((name) => ({ name, kind: 'TABLE' as const,
        columns: [{ name: 'id', position: 1, type: 'uuid', nullable: false, defaultValue: null },
          { name: 'note', position: 2, type: 'text', nullable: true, defaultValue: null }],
        constraints: [{ name: `${name}_pkey`, kind: 'PRIMARY_KEY' as const, columns: ['id'], definition: 'PRIMARY KEY (id)',
          references: null, referencedColumns: [], deferrable: false, validated: true }], indexes: [], triggers: [] })),
    ],
    relationships: [
      { constraint: 'invoices_order_fk', from: 'invoices', fromColumns: ['organization_id', 'order_id'], to: 'orders', toColumns: ['organization_id', 'id'],
        kind: 'AT_MOST_ONE_ACTIVE', evidence: 'DECLARED', via: null, note: 'partial unique index' },
      { constraint: 'role_permissions:a+b', from: 'permissions', fromColumns: ['code'], to: 'roles', toColumns: ['code'],
        kind: 'MANY_TO_MANY', evidence: 'INFERRED', via: 'role_permissions', note: 'Inferred junction' },
    ],
  },
};

function serve(liveStatus = 200) {
  return vi.fn(async (url: string) => {
    const u = new URL(url, 'http://x');
    if (u.pathname === '/api/v1/graph/schema') return liveStatus === 200 ? reply(live) : reply({ status: liveStatus, title: 'Forbidden' }, liveStatus);
    if (u.pathname === '/api/v1/graph/schema/versions') return reply([live.latestSnapshot]);
    if (u.pathname === '/api/v1/graph/schema/diff') return reply({ from: 'snap-1', to: 'live', diff: { added: 1, removed: 0, changed: 0,
      changes: [{ objectType: 'COLUMN', table: 'orders', name: 'delivery_note', change: 'ADDED', before: null, after: 'character varying(500) NULL' }] } });
    return reply({ status: 404 }, 404);
  });
}

describe('SchemaPage', () => {
  beforeEach(() => { api.setAccessToken('t'); });
  afterEach(() => { vi.unstubAllGlobals(); session.admin = false; });

  it('shows a permission-denied state to non-admins without calling the API', async () => {
    const f = serve();
    vi.stubGlobal('fetch', f);
    renderWithProviders(<SchemaPage />);
    expect(screen.getByRole('alert')).toHaveTextContent('platform administrators only');
    expect(f).not.toHaveBeenCalled();
  });

  it('treats a 403 from the server as permission denied, not as an error', async () => {
    session.admin = true;   // e.g. the flag was revoked after the token was issued
    vi.stubGlobal('fetch', serve(403));
    renderWithProviders(<SchemaPage />);
    expect(await screen.findByText('You do not have access to this view')).toBeInTheDocument();
  });

  it('lists catalog tables and relationships with their evidence, and compares versions', async () => {
    session.admin = true;
    vi.stubGlobal('fetch', serve());
    renderWithProviders(<SchemaPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Tables' }));
    const tables = await screen.findByRole('table', { name: 'Tables' });
    expect(within(tables).getByText('View')).toBeInTheDocument();
    const rels = screen.getByRole('table', { name: 'Relationships' });
    expect(within(rels).getByText('One to many, at most one active')).toBeInTheDocument();
    expect(within(rels).getByText('Inferred')).toBeInTheDocument();
    expect(within(rels).getByText('via role_permissions')).toBeInTheDocument();
    expect(screen.getAllByRole('status')[0]).toHaveTextContent('Matches the latest stored snapshot');

    await userEvent.selectOptions(screen.getByLabelText('Relationship kind'), 'MANY_TO_MANY');
    expect(within(screen.getByRole('table', { name: 'Relationships' })).queryByText('One to many, at most one active')).not.toBeInTheDocument();

    await userEvent.click(within(tables).getByRole('button', { name: 'invoices' }));
    expect(await screen.findByText(/where \(\(status\)/)).toBeInTheDocument();

    await userEvent.selectOptions(await screen.findByLabelText('Compare the live schema with'), 'snap-1');
    const changes = await screen.findByRole('table', { name: 'Schema changes' });
    expect(within(changes).getByText('orders.delivery_note')).toBeInTheDocument();
  });
});

describe('SchemaPage ER diagram', () => {
  beforeEach(() => { api.setAccessToken('t'); });
  afterEach(() => { vi.unstubAllGlobals(); session.admin = false; });

  const tableButtons = (region: HTMLElement) => within(region).getAllByRole('button').filter((b) => b.hasAttribute('data-table'));

  it('draws one node per catalog table and one edge per relationship, laid out by ELK', async () => {
    session.admin = true;
    vi.stubGlobal('fetch', serve());
    renderWithProviders(<SchemaPage />);
    const diagram = await screen.findByRole('region', { name: 'ER diagram (scrolls)' });
    expect(tableButtons(diagram).map((b) => b.getAttribute('data-table')).sort())
      .toEqual(['field_changes', 'invoices', 'orders', 'permissions', 'roles']);
    expect(screen.getByText(/5 of 5 tables · 2 relationships/)).toBeInTheDocument();
    expect(within(diagram).getByText('1 : N, ≤ 1 active')).toBeInTheDocument();
    expect(within(diagram).getByText('M : N')).toBeInTheDocument();
    expect(diagram.querySelector('.react-flow__minimap')).not.toBeNull();
    // Key columns first; the rest on demand.
    await userEvent.click(within(diagram).getByRole('button', { name: '+ 1 more column of orders' }));
    expect(within(diagram).getByRole('button', { name: 'Key columns only of orders' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('filters, focuses on a table and its neighbours, and hides inferred edges on request', async () => {
    session.admin = true;
    vi.stubGlobal('fetch', serve());
    renderWithProviders(<SchemaPage />);
    const diagram = await screen.findByRole('region', { name: 'ER diagram (scrolls)' });
    await userEvent.click(screen.getByRole('checkbox', { name: 'Inferred many-to-many' }));
    expect(await screen.findByText(/5 of 5 tables · 1 relationships/)).toBeInTheDocument();
    await userEvent.click(tableButtons(diagram).find((b) => b.getAttribute('data-table') === 'invoices')!);
    expect(screen.getByRole('region', { name: 'Selected table' })).toHaveTextContent('invoices → orders');
    await userEvent.click(screen.getByRole('checkbox', { name: /and its neighbours/ }));
    expect(await screen.findByText(/2 of 5 tables · 1 relationships/)).toBeInTheDocument();
    await userEvent.click(within(screen.getByRole('region', { name: 'Selected table' })).getByRole('button', { name: 'invoices → orders' }));
    expect(screen.getByRole('region', { name: 'Selected relationship' })).toHaveTextContent('partial unique index');
  });

  it('records the export in the audit trail first, and makes no image if that is refused', async () => {
    session.admin = true;
    const calls: string[] = [];
    const base = serve();
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/graph/schema/exports')) {
        calls.push(String(init?.body));
        return new Response(JSON.stringify({ status: 403, title: 'Forbidden' }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } });
      }
      return base(url);
    }));
    renderWithProviders(<SchemaPage />);
    await screen.findByRole('region', { name: 'ER diagram (scrolls)' });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click');
    await userEvent.click(screen.getByRole('button', { name: 'SVG', exact: true } as never));
    expect(JSON.parse(calls[0]!)).toEqual({ format: 'SVG', version: 'live', tables: 5 });
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(click).not.toHaveBeenCalled();
    click.mockRestore();
  });
});
