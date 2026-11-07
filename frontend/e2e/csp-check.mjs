// Content-Security-Policy check for the production build.
//
// Serves frontend/dist with the CSP below, opens pages in Chromium, and fails on any
// CSP violation. The only inline script (the theme bootstrap in index.html) is
// allowed by its SHA-256 hash, computed here from the built file, so the policy and
// the page cannot drift apart. A negative control injects an inline script and
// expects it to be blocked.
//
// Run after `npm run build`: node e2e/csp-check.mjs
// Or against the real web tier (Caddy), which must send exactly this policy:
//   node e2e/csp-check.mjs --base http://127.0.0.1:8080
import { readFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { createRequire } from 'node:module';
import { cspFor } from './csp-policy.mjs';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const DIST = new URL('../dist/', import.meta.url).pathname;
const html = readFileSync(join(DIST, 'index.html'), 'utf8');

export const CSP = cspFor(html);

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/')) {
    // The pages under test only call the refresh endpoint on load; answer as signed out.
    res.writeHead(401, { 'Content-Type': 'application/problem+json' });
    return res.end(JSON.stringify({ status: 401, title: 'Unauthorized' }));
  }
  let file = normalize(join(DIST, url.pathname));
  if (!file.startsWith(DIST) || !existsSync(file) || url.pathname === '/') file = join(DIST, 'index.html');
  res.writeHead(200, {
    'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream',
    'Content-Security-Policy': CSP,
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(readFileSync(file));
});
const at = process.argv.indexOf('--base');
const external = at > 0 ? process.argv[at + 1] : null;
let base = external;
if (external) {
  const served = (await fetch(external + '/sign-in')).headers.get('content-security-policy');
  if (served !== CSP) {
    console.error(`FAIL the server sends a different policy:\n  served:   ${served}\n  expected: ${CSP}`);
    process.exit(1);
  }
  console.log('PASS server sends the policy derived from this build');
} else {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
}

const browser = await chromium.launch();
let failures = 0;
try {
  for (const [path, dark] of [['/sign-in', false], ['/register', false], ['/sign-in', true]]) {
    const ctx = await browser.newContext();
    if (dark) await ctx.addInitScript(() => localStorage.setItem('atlas.theme', 'dark'));
    const page = await ctx.newPage();
    const violations = [];
    page.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) violations.push(m.text()); });
    await page.goto(base + path);
    await page.waitForSelector('form');
    const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
    const ok = violations.length === 0 && isDark === dark;
    if (!ok) failures++;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${path}${dark ? ' (dark theme)' : ''}: ${violations.length} violations, theme as expected: ${isDark === dark}`);
    violations.forEach((v) => console.log('   ', v));
    await ctx.close();
  }
  // Negative control: an injected inline script must be refused.
  const page = await browser.newPage();
  const violations = [];
  page.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) violations.push(m.text()); });
  await page.goto(base + '/sign-in');
  await page.waitForSelector('form');
  const ran = await page.evaluate(() => {
    window.__injected = false;
    const s = document.createElement('script');
    s.textContent = 'window.__injected = true';
    document.body.appendChild(s);
    return window.__injected;
  });
  const blocked = !ran && violations.length > 0;
  if (!blocked) failures++;
  console.log(`${blocked ? 'PASS' : 'FAIL'} injected inline script blocked: ${!ran} (${violations.length} violation reported)`);
} finally {
  await browser.close();
  server.close();
}
console.log(`\nContent-Security-Policy: ${CSP}`);
process.exit(failures ? 1 : 0);
