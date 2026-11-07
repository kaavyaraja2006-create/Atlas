import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import type { Order } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { OrderDetailPage } from './OrderDetailPage';

const session = vi.hoisted(() => ({ perms: new Set<string>() }));
vi.mock('@/session/SessionProvider', () => ({
  Perm: { orderWrite: 'order.write', invoiceWrite: 'invoice.write', auditRead: 'audit.read' },
  useSession: () => ({ current: { id: 'org-1', currency: 'INR' }, can: (p: string) => session.perms.has(p) }),
}));

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } });

const order = (over: Partial<Order> = {}): Order => ({
  id: 'ord-1', organizationId: 'org-1', orderNumber: 'SO-000001', customerId: 'cus-1', status: 'CONFIRMED', totalAmount: 15000,
  createdBy: 'u', placedAt: '2026-11-05T00:00:00Z', createdAt: '2026-11-05T00:00:00Z', updatedAt: '2026-11-05T00:00:00Z',
  version: 4, deliveryNote: null, lines: [], ...over,
});

function serve(o: Order, patch: (body: unknown) => Response) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://x').pathname;
    if (path === '/api/v1/orders/ord-1' && init?.method === 'PATCH') return patch(JSON.parse(String(init.body)));
    if (path === '/api/v1/orders/ord-1') return reply(o);
    if (path === '/api/v1/customers/cus-1') return reply({ id: 'cus-1', name: 'Acme', version: 0 });
    if (path === '/api/v1/invoices') return reply({ items: [], page: { number: 0, size: 20, totalItems: 0, totalPages: 0 } });
    return reply({ status: 404, detail: 'no fixture' }, 404);
  });
}

const show = () => renderWithProviders(<Routes><Route path="/orders/:id" element={<OrderDetailPage />} /></Routes>, { route: '/orders/ord-1' });

describe('OrderDetailPage delivery note', () => {
  beforeEach(() => { api.setAccessToken('t'); api.setOrganization('org-1'); });
  afterEach(() => { vi.unstubAllGlobals(); session.perms = new Set(); });

  it('saves the note against the version on screen', async () => {
    session.perms = new Set(['order.write']);
    const f = serve(order(), (b) => reply(order({ version: 5, deliveryNote: (b as { deliveryNote: string }).deliveryNote })));
    vi.stubGlobal('fetch', f);
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Add note' }));
    await userEvent.type(screen.getByLabelText('Note'), 'Leave at gate 2');
    await userEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect(await screen.findByText('Leave at gate 2')).toBeInTheDocument();
    const call = f.mock.calls.find(([, i]) => i?.method === 'PATCH')!;
    expect(new Headers(call[1]!.headers).get('If-Match')).toBe('"4"');
    expect(JSON.parse(String(call[1]!.body))).toEqual({ deliveryNote: 'Leave at gate 2' });
  });

  it('reports a conflicting edit instead of overwriting it', async () => {
    session.perms = new Set(['order.write']);
    vi.stubGlobal('fetch', serve(order({ deliveryNote: 'old' }), () =>
      reply({ status: 409, title: 'Conflict', detail: 'Order changed since you loaded it' }, 409)));
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect(await screen.findByRole('button', { name: /reload/i })).toBeInTheDocument();
  });

  it('is read-only for a cancelled order or without order.write', async () => {
    session.perms = new Set(['order.write']);
    vi.stubGlobal('fetch', serve(order({ status: 'CANCELLED', deliveryNote: 'final' }), () => reply({}, 500)));
    show();
    expect(await screen.findByText('final')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});
