import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

const origin = 'https://atlas.example';

describe('safeNext', () => {
  it('keeps paths on this site, with their query and fragment', () => {
    expect(safeNext('/orders/42?tab=lines#total', origin)).toBe('/orders/42?tab=lines#total');
    expect(safeNext('/recovery', origin)).toBe('/recovery');
  });

  it.each([
    ['protocol-relative', '//evil.example/x'],
    ['backslash read as a slash', '/\\evil.example'],
    ['two backslashes', '/\\\\evil.example'],
    ['tab dropped by URL parsing', '/\t/evil.example'],
    ['newline dropped by URL parsing', '/\n/evil.example'],
    ['absolute URL', 'https://evil.example/'],
    ['script URL', 'javascript:alert(1)'],
    ['relative path', 'orders/42'],
    ['leading space', ' /orders'],
  ])('sends %s to the home page', (_name, next) => {
    expect(safeNext(next, origin)).toBe('/');
  });

  it('sends a missing value to the home page', () => {
    expect(safeNext(null, origin)).toBe('/');
    expect(safeNext('', origin)).toBe('/');
  });

  it('keeps an encoded slash as a harmless path on this site', () => {
    expect(safeNext('/%2F%2Fevil.example', origin)).toBe('/%2F%2Fevil.example');
  });
});
