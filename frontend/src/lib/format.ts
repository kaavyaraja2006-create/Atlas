const moneyFormats = new Map<string, Intl.NumberFormat>();

/** Formats an amount in the organization's currency, e.g. ₹5,000.00 (Indian digit grouping for INR). */
export function money(amount: number, currency = 'INR'): string {
  let format = moneyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat(currency === 'INR' ? 'en-IN' : 'en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    moneyFormats.set(currency, format);
  }
  return format.format(amount);
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** "Acme Traders Pvt. Ltd." → "acme-traders-pvt-ltd", matching the server's slug rule. */
export function slugify(name: string): string {
  return name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
    .replace(/-+$/g, '');
}

export const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/;

/** Fresh key per user action, so a double-click or network retry never creates two records. */
export function newIdempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
