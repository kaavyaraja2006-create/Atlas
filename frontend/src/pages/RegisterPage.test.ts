import { describe, expect, it } from 'vitest';
import { registerSchema } from './RegisterPage';

const base = { displayName: 'Kaavya', email: 'k@atlas.test', password: 'correct horse battery', confirm: 'correct horse battery' };

describe('registerSchema mirrors the server password policy', () => {
  it('accepts a long passphrase', () => {
    expect(registerSchema.safeParse(base).success).toBe(true);
  });
  it('counts characters, not UTF-16 units, and caps at 72 bytes', () => {
    const emoji = '😀'.repeat(11);
    expect(registerSchema.safeParse({ ...base, password: emoji, confirm: emoji }).success).toBe(false);
    const long = '€'.repeat(25); // 75 bytes
    expect(registerSchema.safeParse({ ...base, password: long, confirm: long }).success).toBe(false);
  });
  it('requires the confirmation to match', () => {
    const r = registerSchema.safeParse({ ...base, confirm: 'different passphrase' });
    expect(r.success).toBe(false);
    expect(r.error?.issues[0]?.path).toEqual(['confirm']);
  });
});
