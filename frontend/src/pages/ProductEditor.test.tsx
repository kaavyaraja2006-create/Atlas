import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { renderWithProviders } from '@/test/render';
import { ProductEditor } from './ProductsPage';

vi.mock('@/session/SessionProvider', () => ({
  Perm: { catalogueWrite: 'catalogue.write' },
  useSession: () => ({
    current: { id: 'org-1', currency: 'INR' },
    can: (p: string) => p === 'catalogue.write',
  }),
}));

const product = {
  id: 'p1', organizationId: 'org-1', sku: 'P100', name: 'Widget', unitPrice: 5000, unitCost: 3000,
  active: true, createdAt: '2026-11-05T10:00:00Z', updatedAt: '2026-11-05T10:00:00Z', version: 4,
};
const reply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('ProductEditor', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    api.setAccessToken('t');
    api.setOrganization('org-1');
  });
  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it('sends If-Match with the loaded version and shows a conflict instead of overwriting', async () => {
    fetchMock
      .mockResolvedValueOnce(reply(200, product))
      .mockResolvedValueOnce(reply(409, { status: 409, title: 'Conflict', detail: 'changed by someone else' }))
      .mockResolvedValueOnce(reply(200, { ...product, unitPrice: 5200, version: 5 }));

    renderWithProviders(<ProductEditor productId="p1" onClose={() => {}} />);
    const price = await screen.findByLabelText('Unit price');
    await userEvent.clear(price);
    await userEvent.type(price, '500');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Someone else changed this record.')).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url).toBe('/api/v1/products/p1');
    expect((init.headers as Record<string, string>)['If-Match']).toBe('"4"');
    expect(JSON.parse(init.body as string)).toMatchObject({ unitPrice: '500' });
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();

    // Reload shows the other person's version (v5) with a fresh form.
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(screen.getByText('v5')).toBeInTheDocument());
    expect(screen.queryByText('Someone else changed this record.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Unit price')).toHaveValue('5200');
  });

  it('validates money input before sending anything', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, product));
    renderWithProviders(<ProductEditor productId="p1" onClose={() => {}} />);
    const price = await screen.findByLabelText('Unit price');
    await userEvent.clear(price);
    await userEvent.type(price, '-5');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Up to 15 digits and 4 decimals, not negative')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
