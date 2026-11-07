/**
 * The path to go to after signing in, from an untrusted `?next=` value.
 *
 * Only a path on this same site is honoured. The value is resolved the way a
 * browser resolves it (WHATWG URL rules: `\` is a `/`, tabs and newlines are
 * dropped), and kept only if the result stays on this origin. A prefix check
 * is not enough: `/\evil.example` starts with one `/` but a browser reads it
 * as `//evil.example`, another site.
 */
export function safeNext(next: string | null, origin: string = window.location.origin): string {
  if (!next || !next.startsWith('/')) return '/';
  let url: URL;
  try {
    url = new URL(next, origin);
  } catch {
    return '/';
  }
  if (url.origin !== origin) return '/';
  return `${url.pathname}${url.search}${url.hash}`;
}
