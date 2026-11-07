import { describe, expect, it } from 'vitest';
import { diffRows, edgeSentence, entityLabel, fieldLabel, formatValue, historyPath, recordPath } from './audit';

describe('diffRows', () => {
  it('puts the server-reported changed fields first and keeps the rest for context', () => {
    const rows = diffRows(
      { name: 'Widget', unit_price: 5000, active: true },
      { name: 'Widget', unit_price: 500, active: true },
      ['unit_price'],
    );
    expect(rows.map((r) => [r.field, r.changed])).toEqual([
      ['unit_price', true], ['active', false], ['name', false],
    ]);
    expect(rows[0]).toMatchObject({ before: 5000, after: 500 });
  });

  it('trusts the server on numeric equality (500 vs 500.0000 is not a change)', () => {
    const rows = diffRows({ unit_price: 500 }, { unit_price: 500.0 }, []);
    expect(rows[0]?.changed).toBe(false);
  });

  it('handles inserts (no before) and fields present on one side only', () => {
    const rows = diffRows(null, { sku: 'P100' }, ['sku']);
    expect(rows).toEqual([{ field: 'sku', before: undefined, after: 'P100', changed: true }]);
    const added = diffRows({ a: 1 }, { a: 1, b: 2 }, ['b']);
    expect(added.find((r) => r.field === 'b')).toMatchObject({ before: undefined, after: 2, changed: true });
  });
});

describe('formatting and links', () => {
  it('formats values', () => {
    expect(formatValue(null)).toBe('—');
    expect(formatValue(undefined)).toBe('');
    expect(formatValue(false)).toBe('false');
    expect(formatValue(['ORGANIZATION_ADMIN', 'AUDITOR'])).toBe('ORGANIZATION_ADMIN, AUDITOR');
    expect(formatValue({ a: 1 })).toBe('{"a":1}');
  });

  it('labels entities and builds history links', () => {
    expect(entityLabel('order_line')).toBe('Order line');
    expect(fieldLabel('sku')).toBe('SKU');
    expect(fieldLabel('order_id')).toBe('Order ID');
    expect(fieldLabel('unit_price')).toBe('Unit price');
    expect(entityLabel('mystery_thing')).toBe('mystery thing');
    expect(historyPath('order_line', 'abc', 0)).toBe('/history/order_line/abc?version=0');
  });

  it('links lines to their parent document using only ids in the snapshot', () => {
    expect(recordPath('order_line', 'l1', { order_id: 'o1' })).toBe('/orders/o1');
    expect(recordPath('invoice_line', 'il1', { invoice_id: 'i1' })).toBe('/invoices/i1');
    expect(recordPath('order_line', 'l1', null)).toBeNull();
    expect(recordPath('product', 'p1', { sku: 'P100' })).toBeNull();
  });
});

describe('edgeSentence', () => {
  const priced = { relation: 'DERIVED_VALUE' as const, sourceField: 'unit_price', targetField: 'unit_price' };
  const summed = { relation: 'AGGREGATED_INTO' as const, sourceField: 'line_total', targetField: 'total_amount' };
  const copied = { relation: 'COPIED_INTO' as const, sourceField: 'unit_price', targetField: 'unit_price' };

  it('reads correctly from the derived record (upstream)', () => {
    expect(edgeSentence(priced, 'upstream')).toBe('This unit price was derived from its unit price');
    expect(edgeSentence(summed, 'upstream')).toBe('This total amount includes its line total');
    expect(edgeSentence(copied, 'upstream')).toBe('This unit price was copied from its unit price');
  });

  it('reads correctly from the source record (downstream)', () => {
    expect(edgeSentence(priced, 'downstream')).toBe('Its unit price was derived from this unit price');
    expect(edgeSentence(summed, 'downstream')).toBe('This line total is included in its total amount');
    expect(edgeSentence(copied, 'downstream')).toBe('This unit price was copied into its unit price');
  });
});
