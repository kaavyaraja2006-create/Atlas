import { describe, expect, it } from 'vitest';
import { SLUG_PATTERN, money, newIdempotencyKey, slugify } from './format';

describe('money', () => {
  it('uses Indian digit grouping for INR', () => {
    expect(money(1234567.5)).toBe('₹12,34,567.50');
    expect(money(500)).toBe('₹500.00');
  });
  it('formats other currencies', () => {
    expect(money(1234.5, 'USD')).toBe('$1,234.50');
  });
});

describe('slugify', () => {
  it('produces slugs the server accepts', () => {
    for (const name of ['Acme Traders Pvt. Ltd.', '  Café Ünïcode  ', 'TechNova', 'x'.repeat(100)]) {
      const slug = slugify(name);
      expect(slug.length).toBeLessThanOrEqual(64);
      if (slug.length >= 3) expect(SLUG_PATTERN.test(slug)).toBe(true);
    }
    expect(slugify('Acme Traders Pvt. Ltd.')).toBe('acme-traders-pvt-ltd');
    expect(slugify('Café Ünïcode')).toBe('cafe-unicode');
  });
});

describe('newIdempotencyKey', () => {
  it('is unique and matches the server rule', () => {
    const a = newIdempotencyKey('order');
    expect(a).not.toBe(newIdempotencyKey('order'));
    expect(/^[A-Za-z0-9._:-]{8,128}$/.test(a)).toBe(true);
  });
});
