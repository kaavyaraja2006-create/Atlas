import type {
  Confidence, ConflictKind, IntegrityStatus, LineageEdge, RiskLevel, StrategyName, VersionRef,
} from '@/api/types';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

/* ---------------------------------------------------------------- provenance confidence */

export const confidenceLabel: Record<Confidence, string> = {
  VERIFIED: 'Verified',
  DECLARED: 'Declared',
  INFERRED: 'Inferred',
  UNKNOWN: 'Unknown',
};

export const confidenceHelp: Record<Confidence, string> = {
  VERIFIED: 'Recorded when the value was written, and the recorded values agree with it.',
  DECLARED: 'Recorded when the value was written, but the values do not confirm it.',
  INFERRED: 'Added afterwards by a backfill; never used for automatic recovery.',
  UNKNOWN: 'Required provenance is missing.',
};

export const confidenceTone: Record<Confidence, Tone> = {
  VERIFIED: 'success', DECLARED: 'info', INFERRED: 'warning', UNKNOWN: 'danger',
};

/** Line style per confidence, so the graph never relies on colour alone. */
export const confidenceDash: Record<Confidence, string | undefined> = {
  VERIFIED: undefined, DECLARED: '6 4', INFERRED: '2 4', UNKNOWN: '1 6',
};

const STRENGTH: Record<Confidence, number> = { VERIFIED: 3, DECLARED: 2, INFERRED: 1, UNKNOWN: 0 };

/* ---------------------------------------------------------------- lineage layout */

export function refKey(r: VersionRef): string {
  return `${r.entityType}:${r.entityId}@${r.version}`;
}

export interface LayoutNode {
  key: string;
  ref: VersionRef;
  column: number;
  row: number;
  root: boolean;
}

export interface LayoutLink {
  from: string;
  to: string;
  /** Every recorded field mapping between the two versions. */
  edges: LineageEdge[];
  /** The weakest confidence among them: a link is only as strong as its weakest evidence. */
  confidence: Confidence;
}

export interface LineageLayout {
  nodes: LayoutNode[];
  links: LayoutLink[];
  columns: number;
  rows: number;
}

/**
 * Places lineage in columns by hop distance from the record the user is looking at:
 * downstream flows left to right, upstream right to left (the record stays at the
 * edge it started from). Only edges the server returned are drawn; nothing is inferred
 * here. Deterministic order within a column (type, then id, then version).
 */
export function layoutLineage(edges: LineageEdge[], direction: 'downstream' | 'upstream'): LineageLayout {
  const column = new Map<string, number>();
  const refs = new Map<string, VersionRef>();
  const rootKeys = new Set<string>();
  for (const e of edges) {
    const near = direction === 'downstream' ? e.source : e.target;
    const far = direction === 'downstream' ? e.target : e.source;
    refs.set(refKey(near), near);
    refs.set(refKey(far), far);
    if (e.depth === 1) rootKeys.add(refKey(near));
    const nearCol = e.depth - 1;
    const farCol = e.depth;
    column.set(refKey(near), Math.min(column.get(refKey(near)) ?? nearCol, nearCol));
    column.set(refKey(far), Math.min(column.get(refKey(far)) ?? farCol, farCol));
  }
  const maxCol = Math.max(0, ...column.values());
  const byColumn = new Map<number, string[]>();
  for (const [k, c] of column) byColumn.set(c, [...(byColumn.get(c) ?? []), k]);
  const nodes: LayoutNode[] = [];
  let rows = 0;
  for (const [c, keysInColumn] of byColumn) {
    keysInColumn.sort();
    keysInColumn.forEach((k, row) => {
      nodes.push({
        key: k, ref: refs.get(k)!, row, root: rootKeys.has(k),
        column: direction === 'downstream' ? c : maxCol - c,
      });
    });
    rows = Math.max(rows, keysInColumn.length);
  }
  nodes.sort((a, b) => a.column - b.column || a.row - b.row);

  const grouped = new Map<string, LineageEdge[]>();
  for (const e of edges) {
    const id = `${refKey(e.source)}>${refKey(e.target)}`;
    grouped.set(id, [...(grouped.get(id) ?? []), e]);
  }
  const links: LayoutLink[] = [...grouped.values()].map((group) => ({
    from: refKey(group[0]!.source),
    to: refKey(group[0]!.target),
    edges: group,
    confidence: group.reduce<Confidence>((w, e) => (STRENGTH[e.confidence] < STRENGTH[w] ? e.confidence : w), 'VERIFIED'),
  }));
  return { nodes, links, columns: maxCol + 1, rows };
}

/* ---------------------------------------------------------------- conflicts */

export const conflictLabel: Record<ConflictKind, string> = {
  NO_CONFLICT: 'No conflict',
  SAME_FIELD_CONFLICT: 'Same field changed later',
  DIFFERENT_FIELD_UPDATE: 'Other fields changed later',
  SOURCE_VERSION_MISMATCH: 'Re-derived from another source',
  CONCURRENT_MODIFICATION: 'Changed after planning',
  UNKNOWN_CHANGE_ORIGIN: 'Changed by an unknown origin',
  REQUIRES_MANUAL_REVIEW: 'Evidence incomplete',
};

export const conflictTone: Record<ConflictKind, Tone> = {
  NO_CONFLICT: 'success',
  DIFFERENT_FIELD_UPDATE: 'info',
  SOURCE_VERSION_MISMATCH: 'info',
  SAME_FIELD_CONFLICT: 'danger',
  CONCURRENT_MODIFICATION: 'danger',
  UNKNOWN_CHANGE_ORIGIN: 'warning',
  REQUIRES_MANUAL_REVIEW: 'warning',
};

export const reviewDecisionLabel = {
  KEEP_LATER_CHANGE: 'Keep the later change',
  RECOVERY_MAY_OVERWRITE: 'Recovery may overwrite it',
  NEEDS_INVESTIGATION: 'Needs investigation',
} as const;

/* ---------------------------------------------------------------- simulation and risk */

export const STRATEGY_ORDER: StrategyName[] = [
  'FULL_RESTORE', 'NAIVE_FK_ROW', 'VERSION_ROW_RESTORE', 'FIELD_LEVEL_FK',
  'PROVENANCE_CONFLICT_BLIND', 'PROVENANCE_CONFLICT_AWARE',
];

export const strategyLabel: Record<StrategyName, string> = {
  FULL_RESTORE: 'Point-in-time restore',
  NAIVE_FK_ROW: 'Foreign keys, whole rows',
  VERSION_ROW_RESTORE: 'Provenance, whole rows',
  FIELD_LEVEL_FK: 'Foreign keys, single fields',
  PROVENANCE_CONFLICT_BLIND: 'Provenance, conflicts ignored',
  PROVENANCE_CONFLICT_AWARE: 'PASCAR (provenance + conflicts)',
};

export const riskTone: Record<RiskLevel, Tone> = {
  LOW: 'success', MEDIUM: 'info', HIGH: 'warning', CRITICAL: 'danger',
};

export const constraintLabel: Record<string, string> = {
  APPLICABLE: 'Applicable',
  INVARIANTS_HOLD: 'Business rules hold',
  NO_COLLATERAL: 'No collateral changes',
  PRESERVES_LATER_UPDATES: 'Keeps later updates',
  NO_DELETIONS: 'Deletes nothing',
  VERSION_CHECKED_WRITES: 'Version-checked writes',
  CONFLICTS_GO_TO_PEOPLE: 'Conflicts go to people',
  AUTHORIZED_EXECUTION: 'Approval workflow',
};

/* ---------------------------------------------------------------- integrity */

export const integrityStatusTone: Record<IntegrityStatus, Tone> = {
  OPEN: 'danger', ACKNOWLEDGED: 'warning', RESOLVED: 'success', CLEARED: 'neutral',
};

/** Records with a history page; others (an organization) have none. */
export function hasHistory(entityType: string): boolean {
  return ['product', 'customer', 'order', 'order_line', 'invoice', 'invoice_line', 'credit_note'].includes(entityType);
}

export function operationLabel(op: string | null): string {
  return op ?? 'not recorded';
}
