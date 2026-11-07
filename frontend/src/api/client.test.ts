import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, query } from './client';

const json = (status: number, body: unknown, type = 'application/json') =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': type } });

function headersOf(call: unknown[]): Record<string, string> {
  return (call[1] as RequestInit).headers as Record<string, string>;
}

describe('ApiClient', () => {
  it('sends bearer token, tenant header, If-Match and Idempotency-Key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, { ok: true }));
    const client = new ApiClient(fetchMock);
    client.setAccessToken('t1');
    client.setOrganization('org-1');
    await client.request('/api/v1/products/p', { method: 'PATCH', body: { a: 1 }, ifMatchVersion: 7, idempotencyKey: 'key-12345678' });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/products/p');
    expect(init.method).toBe('PATCH');
    expect(init.body).toBe('{"a":1}');
    expect(init.credentials).toBe('same-origin');
    expect(headersOf(fetchMock.mock.calls[0]!)).toMatchObject({
      Authorization: 'Bearer t1',
      'X-Organization-Id': 'org-1',
      'If-Match': '"7"',
      'Idempotency-Key': 'key-12345678',
      'Content-Type': 'application/json',
    });
  });

  it('omits the tenant header for untenanted calls', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, []));
    const client = new ApiClient(fetchMock);
    client.setOrganization('org-1');
    await client.request('/api/v1/organizations', { untenanted: true });
    expect(headersOf(fetchMock.mock.calls[0]!)['X-Organization-Id']).toBeUndefined();
  });

  it('turns problem+json into an ApiError with field errors and correlation id', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(400, {
      status: 400, title: 'Validation failed', detail: 'One or more fields are invalid.',
      errors: ['unitPrice: must be greater than or equal to 0'], correlationId: 'cid-9',
    }, 'application/problem+json'));
    const error = await new ApiClient(fetchMock).request('/api/v1/products').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.message).toBe('One or more fields are invalid.');
    expect(apiError.errors).toEqual(['unitPrice: must be greater than or equal to 0']);
    expect(apiError.correlationId).toBe('cid-9');
  });

  it('flags 409 as a conflict and survives a non-JSON error body', async () => {
    const conflict = (await new ApiClient(vi.fn().mockResolvedValue(json(409, { status: 409, detail: 'changed' })))
      .request('/x').catch((e: unknown) => e)) as ApiError;
    expect(conflict.isConflict).toBe(true);
    const gateway = (await new ApiClient(vi.fn().mockResolvedValue(new Response('<html>bad gateway</html>', { status: 502 })))
      .request('/x').catch((e: unknown) => e)) as ApiError;
    expect(gateway.status).toBe(502);
  });

  it('refreshes once on 401 and retries the request with the new token', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(401, { status: 401 }))
      .mockResolvedValueOnce(json(200, { accessToken: 't2', tokenType: 'Bearer', expiresAt: '' }))
      .mockResolvedValueOnce(json(200, { id: 'me' }));
    const client = new ApiClient(fetchMock);
    client.setAccessToken('expired');
    await expect(client.request('/api/v1/users/me')).resolves.toEqual({ id: 'me' });
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/v1/auth/refresh');
    expect(headersOf(fetchMock.mock.calls[2]!).Authorization).toBe('Bearer t2');
  });

  it('shares one refresh between concurrent 401s', async () => {
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        refreshCalls += 1;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, { accessToken: 'fresh', tokenType: 'Bearer', expiresAt: '' });
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, { url }) : json(401, { status: 401 });
    });
    const client = new ApiClient(fetchMock as unknown as typeof fetch);
    client.setAccessToken('expired');
    const results = await Promise.all([client.request('/a'), client.request('/b'), client.request('/c')]);
    expect(results).toEqual([{ url: '/a' }, { url: '/b' }, { url: '/c' }]);
    expect(refreshCalls).toBe(1);
  });

  it('ends the session when refresh fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(401, { status: 401, detail: 'expired' }))
      .mockResolvedValueOnce(json(401, { status: 401 }));
    const client = new ApiClient(fetchMock);
    const expired = vi.fn();
    client.onSessionExpired = expired;
    client.setAccessToken('expired');
    await expect(client.request('/api/v1/products')).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
    expect(client.hasSession()).toBe(false);
  });

  it('never tries to refresh for auth endpoints (a wrong password is just a 401)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(401, { status: 401, detail: 'Invalid email or password' }));
    const client = new ApiClient(fetchMock);
    client.setAccessToken('t');
    await expect(client.login('a@b.c', 'x')).rejects.toMatchObject({ message: 'Invalid email or password' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps tokens only in memory', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, { accessToken: 'secret', tokenType: 'Bearer', expiresAt: '' }));
    const client = new ApiClient(fetchMock);
    await client.login('a@b.c', 'pw');
    expect(client.hasSession()).toBe(true);
    expect(JSON.stringify(window.localStorage)).not.toContain('secret');
    expect(JSON.stringify(window.sessionStorage)).not.toContain('secret');
  });
});

describe('query', () => {
  it('skips empty values and encodes the rest', () => {
    expect(query({ q: 'a b', page: 0, active: false, status: undefined, x: '' })).toBe('?q=a+b&page=0&active=false');
    expect(query({})).toBe('');
  });
});
