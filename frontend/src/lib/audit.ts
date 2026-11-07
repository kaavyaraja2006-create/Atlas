import type { JsonValue, Snapshot } from '@/api/types';

export interface DiffRow {
  field: string;
  before: JsonValue | undefined;
  after: JsonValue | undefined;
  changed: boolean;
}

/**
 * Rows for a field-level before/after view. The server's changedFields list is
 * authoritative (it compares numbers by value, so 500 and 500.0000 are equal);
 * changed fields come first, then the unchanged ones for context.
 */
export function diffRows(before: Snapshot | null, after: Snapshot | null, changedFields: string[]): DiffRow[] {
  const changed = new Set(changedFields);
  const fields = new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
  const rows = [...fields].map((field) => ({
    field,
    before: before ? before[field] : undefined,
    after: after ? after[field] : undefined,
    changed: changed.has(field),
  }));
  return rows.sort((a, b) => Number(b.changed) - Number(a.changed) || a.field.localeCompare(b.field));
}

export function formatValue(value: JsonValue | undefined): string {
  if (value === undefined) return '';
  if (value === null) return '—';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (Array.isArray(value)) return value.map((v) => formatValue(v)).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

const labels: Record<string, string> = {
  product: 'Product',
  customer: 'Customer',
  order: 'Order',
  order_line: 'Order line',
  invoice: 'Invoice',
  invoice_line: 'Invoice line',
  credit_note: 'Credit note',
  organization: 'Organization',
  membership: 'Membership',
  recovery_incident: 'Recovery incident',
  recovery_plan: 'Recovery plan',
};

export const ENTITY_TYPES = Object.keys(labels);

export function entityLabel(entityType: string): string {
  return labels[entityType] ?? entityType.replace(/_/g, ' ');
}

const ACRONYMS: Record<string, string> = { sku: 'SKU', id: 'ID' };

export function fieldLabel(field: string): string {
  const words = field.split('_').map((w) => ACRONYMS[w] ?? w).join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

export function historyPath(entityType: string, entityId: string, version?: number): string {
  const base = `/history/${encodeURIComponent(entityType)}/${entityId}`;
  return version === undefined ? base : `${base}?version=${version}`;
}

/** Where the record itself can be opened in the app, if it has a page. Uses only ids the snapshot holds. */
export function recordPath(entityType: string, entityId: string, snapshot: Snapshot | null): string | null {
  const str = (key: string) => {
    const v = snapshot?.[key];
    return typeof v === 'string' ? v : null;
  };
  switch (entityType) {
    case 'order':
      return `/orders/${entityId}`;
    case 'invoice':
      return `/invoices/${entityId}`;
    case 'order_line':
      return str('order_id') ? `/orders/${str('order_id')}` : null;
    case 'invoice_line':
    case 'credit_note':
      return str('invoice_id') ? `/invoices/${str('invoice_id')}` : null;
    default:
      return null;
  }
}

type Relation = 'DERIVED_VALUE' | 'AGGREGATED_INTO' | 'COPIED_INTO';

const f = (field: string | null) => fieldLabel(field ?? 'value').toLowerCase();

/**
 * One sentence per edge, worded from the point of view of the record being viewed.
 * upstream: the other record is the source this version was computed from.
 * downstream: the other record was computed from this version.
 */
export function edgeSentence(
  edge: { relation: Relation; sourceField: string | null; targetField: string | null },
  direction: 'upstream' | 'downstream',
): string {
  const src = f(edge.sourceField);
  const dst = f(edge.targetField);
  if (direction === 'upstream') {
    switch (edge.relation) {
      case 'DERIVED_VALUE': return `This ${dst} was derived from its ${src}`;
      case 'COPIED_INTO': return `This ${dst} was copied from its ${src}`;
      case 'AGGREGATED_INTO': return `This ${dst} includes its ${src}`;
    }
  }
  switch (edge.relation) {
    case 'DERIVED_VALUE': return `Its ${dst} was derived from this ${src}`;
    case 'COPIED_INTO': return `This ${src} was copied into its ${dst}`;
    case 'AGGREGATED_INTO': return `This ${src} is included in its ${dst}`;
  }
}
