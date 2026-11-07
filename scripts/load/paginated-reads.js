// k6 load test for NFR-PERF-1: p95 < 300 ms for paginated reads at 50 concurrent users.
//
// NOT YET RUN: it needs a running backend (see docs/STATUS.md). Prepare a user who is a
// member of an organization with data (scripts/bench can load a synthetic one). All 50
// virtual users share that one account, about 6,000 requests a minute: ten times the
// default per-user API limit, so start the backend for the test with
// ATLAS_RATELIMIT_APIPERMINUTE=100000 (atlas.rate-limit.api-per-minute). Then:
//
//   k6 run -e BASE_URL=https://atlas.example -e EMAIL=... -e PASSWORD=... -e ORG_ID=... \
//          scripts/load/paginated-reads.js
//
// The run fails (non-zero exit) if a threshold is missed.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    paginated_reads: {
      executor: 'constant-vus',
      vus: 50,
      duration: __ENV.DURATION || '5m',
    },
  },
  thresholds: {
    // NFR-PERF-1, over every paginated read:
    'http_req_duration{kind:paginated}': ['p(95)<300'],
    // and no hidden errors behind good latency:
    'checks{kind:paginated}': ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email: __ENV.EMAIL, password: __ENV.PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'signed in': (r) => r.status === 200 });
  if (res.status !== 200) throw new Error(`login failed: ${res.status}`);
  // The access token lives 15 minutes; keep DURATION below that or add a refresh.
  return { token: res.json('accessToken'), org: __ENV.ORG_ID };
}

const LISTS = [
  '/api/v1/orders?sort=createdAt,desc',
  '/api/v1/orders?status=CONFIRMED',
  '/api/v1/invoices?status=ISSUED',
  '/api/v1/products?q=widget',
  '/api/v1/customers',
  '/api/v1/audit/events',
  '/api/v1/audit/events?entityType=invoice',
];

export default function (data) {
  const params = {
    headers: { Authorization: `Bearer ${data.token}`, 'X-Organization-Id': data.org },
    tags: { kind: 'paginated' },
  };
  const path = LISTS[Math.floor(Math.random() * LISTS.length)];
  const page = Math.floor(Math.random() * 20);   // the first 20 pages, as people browse
  const sep = path.includes('?') ? '&' : '?';
  const res = http.get(`${BASE}${path}${sep}page=${page}&size=20`, Object.assign({}, params, {
    tags: { kind: 'paginated', list: path.split('?')[0] },
  }));
  check(res, {
    'status 200': (r) => r.status === 200,
    'is a page': (r) => r.status === 200 && Array.isArray(r.json('items')),
  }, { kind: 'paginated' });
  sleep(0.5);
}
