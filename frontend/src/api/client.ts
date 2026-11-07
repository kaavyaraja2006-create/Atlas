import type { AccessToken, Problem } from './types';

/** A failed API call, carrying the server's problem details. */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly errors: string[];
  readonly correlationId: string | undefined;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed (${problem.status})`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.title = problem.title ?? 'Error';
    this.errors = problem.errors ?? [];
    this.correlationId = problem.correlationId;
  }

  get isConflict() {
    return this.status === 409;
  }

  get isForbidden() {
    return this.status === 403;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Record version the client last saw; sent as a strong ETag in If-Match. */
  ifMatchVersion?: number;
  idempotencyKey?: string;
  /** Skip X-Organization-Id (identity and organization-list endpoints). */
  untenanted?: boolean;
  signal?: AbortSignal;
}

type Fetch = typeof fetch;

/**
 * The single place the SPA talks to the API.
 *
 * - The access token lives only in memory (never localStorage), so an XSS
 *   bug cannot read a persisted token. The refresh token is an HttpOnly
 *   cookie the browser sends only to /api/v1/auth.
 * - A 401 triggers ONE refresh shared by every request that hit it, then
 *   each request is retried once. A failed refresh ends the session.
 */
export class ApiClient {
  private accessToken: string | null = null;
  private organizationId: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  private readonly fetchImpl: Fetch;

  onSessionExpired: () => void = () => {};

  constructor(fetchImpl: Fetch = (...args) => fetch(...args), private readonly baseUrl = '') {
    this.fetchImpl = fetchImpl;
  }

  setAccessToken(token: string | null) {
    this.accessToken = token;
  }

  hasSession() {
    return this.accessToken !== null;
  }

  setOrganization(id: string | null) {
    this.organizationId = id;
  }

  get currentOrganization() {
    return this.organizationId;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.send(path, options);
    if (response.status === 401 && this.accessToken !== null && !path.startsWith('/api/v1/auth/')) {
      if (await this.refreshOnce()) {
        return this.parse<T>(await this.send(path, options));
      }
      this.onSessionExpired();
    }
    return this.parse<T>(response);
  }

  /** Uses the refresh cookie. Returns false when there is no valid session. */
  async refresh(): Promise<boolean> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/auth/refresh`, {
      method: 'POST',
      credentials: 'same-origin',
    });
    if (!response.ok) {
      this.accessToken = null;
      return false;
    }
    const token = (await response.json()) as AccessToken;
    this.accessToken = token.accessToken;
    return true;
  }

  async login(email: string, password: string): Promise<void> {
    const token = await this.request<AccessToken>('/api/v1/auth/login', {
      method: 'POST',
      body: { email, password },
      untenanted: true,
    });
    this.accessToken = token.accessToken;
  }

  async logout(): Promise<void> {
    try {
      await this.request<void>('/api/v1/auth/logout', { method: 'POST', untenanted: true });
    } finally {
      this.accessToken = null;
      this.organizationId = null;
    }
  }

  private refreshOnce(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = this.refresh().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private send(path: string, options: RequestOptions): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;
    if (!options.untenanted && this.organizationId) headers['X-Organization-Id'] = this.organizationId;
    if (options.ifMatchVersion !== undefined) headers['If-Match'] = `"${options.ifMatchVersion}"`;
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
    const init: RequestInit = {
      method: options.method ?? 'GET',
      headers,
      credentials: 'same-origin',
    };
    if (options.body !== undefined) init.body = JSON.stringify(options.body);
    if (options.signal) init.signal = options.signal;
    return this.fetchImpl(`${this.baseUrl}${path}`, init);
  }

  private async parse<T>(response: Response): Promise<T> {
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    const body: unknown = text ? safeJson(text) : undefined;
    if (!response.ok) {
      const problem =
        body && typeof body === 'object' && 'status' in body
          ? (body as Problem)
          : { status: response.status, title: response.statusText || 'Error' };
      throw new ApiError({ ...problem, status: response.status });
    }
    return body as T;
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Builds a query string, skipping empty values. */
export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

export const api = new ApiClient();
