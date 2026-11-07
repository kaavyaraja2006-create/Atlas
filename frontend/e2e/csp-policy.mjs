// The production Content-Security-Policy, derived from the built index.html: the only
// inline script (the theme bootstrap) is allowed by its SHA-256 hash, so the policy and
// the page cannot drift apart. Used by e2e/csp-check.mjs and by the web image build.
//
// Run directly to print the policy for frontend/dist: node e2e/csp-policy.mjs [dist-dir]
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export function cspFor(html) {
  const hashes = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)]
    .map((m) => `'sha256-${createHash('sha256').update(m[1], 'utf8').digest('base64')}'`);
  return [
    "default-src 'self'",
    `script-src 'self' ${hashes.join(' ')}`.trim(),
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dist = process.argv[2] ?? new URL('../dist/', import.meta.url).pathname;
  process.stdout.write(cspFor(readFileSync(join(dist, 'index.html'), 'utf8')));
}
