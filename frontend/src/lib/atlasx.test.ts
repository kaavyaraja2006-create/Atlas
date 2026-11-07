import { describe, expect, it } from 'vitest';
import type { Confidence, LineageEdge } from '@/api/types';
import { layoutLineage, refKey } from './atlasx';

const ref = (entityType: string, entityId: string, version: number) => ({ entityType, entityId, version });
const edge = (depth: number, s: ReturnType<typeof ref>, sf: string, t: ReturnType<typeof ref>, tf: string,
  confidence: Confidence = 'VERIFIED'): LineageEdge => ({
  depth, source: s, sourceField: sf, target: t, targetField: tf, relation: 'DERIVED_VALUE', operation: 'order.add_line',
  evidence: 'RECORDED_AT_WRITE', confidence, sourceValue: '1', targetValue: '1', txnId: 't', actorKind: 'USER',
  actorUserId: null, correlationId: null, recordedAt: '2026-11-06T10:00:00Z',
});

const P = ref('product', 'p', 1);
const L = ref('order_line', 'l', 0);
const O = ref('order', 'o', 1);
const L2 = ref('order_line', 'm', 0);

describe('layoutLineage', () => {
  it('puts the record first and each hop in the next column, downstream left to right', () => {
    const lay = layoutLineage([edge(1, P, 'unit_price', L, 'unit_price'), edge(2, L, 'line_total', O, 'total_amount')], 'downstream');
    const col = Object.fromEntries(lay.nodes.map((n) => [n.key, n.column]));
    expect(col).toEqual({ [refKey(P)]: 0, [refKey(L)]: 1, [refKey(O)]: 2 });
    expect(lay.nodes.find((n) => n.key === refKey(P))?.root).toBe(true);
    expect(lay.columns).toBe(3);
  });

  it('mirrors upstream so the record stays on the right', () => {
    const lay = layoutLineage([edge(1, L, 'line_total', O, 'total_amount'), edge(2, P, 'unit_price', L, 'unit_price')], 'upstream');
    const col = Object.fromEntries(lay.nodes.map((n) => [n.key, n.column]));
    expect(col[refKey(O)]).toBe(2);
    expect(col[refKey(P)]).toBe(0);
  });

  it('draws one link per pair of versions, as weak as its weakest field mapping', () => {
    const lay = layoutLineage([
      edge(1, L, 'unit_price', O, 'total_amount', 'VERIFIED'),
      edge(1, L, 'line_total', O, 'total_amount', 'DECLARED'),
      edge(1, L2, 'line_total', O, 'total_amount', 'INFERRED'),
    ], 'downstream');
    expect(lay.links).toHaveLength(2);
    const fromL = lay.links.find((k) => k.from === refKey(L))!;
    expect(fromL.edges).toHaveLength(2);
    expect(fromL.confidence).toBe('DECLARED');
    expect(lay.links.find((k) => k.from === refKey(L2))!.confidence).toBe('INFERRED');
    expect(lay.rows).toBe(2);
  });

  it('places each version once even on a cycle, at its shortest distance', () => {
    const A = ref('order', 'a', 0);
    const B = ref('order', 'b', 0);
    const lay = layoutLineage([edge(1, A, 'total_amount', B, 'total_amount'), edge(2, B, 'total_amount', A, 'total_amount')], 'downstream');
    expect(lay.nodes.map((n) => n.key).sort()).toEqual([refKey(A), refKey(B)].sort());
    expect(lay.nodes.find((n) => n.key === refKey(A))!.column).toBe(0);
  });

  it('shows nothing when there is nothing recorded', () => {
    expect(layoutLineage([], 'downstream')).toEqual({ nodes: [], links: [], columns: 1, rows: 0 });
  });
});
