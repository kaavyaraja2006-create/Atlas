import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Crosshair, GitBranchPlus, Table2, Workflow } from 'lucide-react';
import { Suspense, lazy, useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keys, provenance } from '@/api/endpoints';
import type { Confidence, Lineage, LineageEdge, VersionRef } from '@/api/types';
import { Badge, Button, Card, ErrorState, Field, InlineError, Input, Modal, Select, Spinner, Table, Td, Th } from '@/components/ui';
import { entityLabel, fieldLabel, historyPath, shortId } from '@/lib/audit';
import {
  confidenceDash, confidenceHelp, confidenceLabel, confidenceTone, layoutLineage, operationLabel, refKey,
} from '@/lib/atlasx';
import { cn, dateTime } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

/** React Flow, ELK and html-to-image load only when a graph is shown. */
const LineageCanvas = lazy(() => import('./lineage/LineageCanvas'));

const CONFIDENCES: Confidence[] = ['VERIFIED', 'DECLARED', 'INFERRED'];
/** More records than this are not drawn at once: the closest hops are, and the rest stay in the table. */
export const MAX_RENDERED_NODES = 300;

const edgeId = (e: LineageEdge) => `${refKey(e.source)}.${e.sourceField}>${refKey(e.target)}.${e.targetField}`;

/**
 * Multi-hop, field-level lineage of one record version (ATLAS X; React Flow since Graph
 * Intelligence). Every line is an edge the server returned from recorded provenance, labelled
 * with its confidence; nothing is drawn that was not recorded. The table view carries the same
 * information as text, and the selection is shared between the two.
 */
export function LineageExplorer({ entityType, entityId, version }: { entityType: string; entityId: string; version: number }) {
  const { current } = useSession();
  const orgId = current?.id ?? null;
  const qc = useQueryClient();
  const [direction, setDirection] = useState<'downstream' | 'upstream'>('downstream');
  const [depth, setDepth] = useState(4);
  const [shown, setShown] = useState<Record<Confidence, boolean>>({ VERIFIED: true, DECLARED: true, INFERRED: true, UNKNOWN: true });
  const [search, setSearch] = useState('');
  const [onlyMatches, setOnlyMatches] = useState(false);
  const [asTable, setAsTable] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [details, setDetails] = useState<string | null>(null);
  /** Edges added by expanding nodes, keyed by direction so switching direction starts clean. */
  const [expanded, setExpanded] = useState<{ direction: string; root: string; edges: LineageEdge[]; from: string[] }>(
    { direction: '', root: '', edges: [], from: [] });
  const [expandError, setExpandError] = useState<unknown>(null);
  const [expanding, setExpanding] = useState<string | null>(null);

  const rootKey = `${entityType}:${entityId}@${version}`;
  const lineage = useQuery({
    queryKey: keys.scoped(orgId, 'lineage', entityType, entityId, version, direction, depth),
    queryFn: () => provenance.lineage(entityType, entityId, direction, depth, version),
  });
  const extra = expanded.direction === direction && expanded.root === rootKey ? expanded : { edges: [], from: [] as string[] };

  // All edges (server + expansions), deduplicated, each at its shortest hop distance from the record.
  const allEdges = useMemo(() => {
    const byId = new Map<string, LineageEdge>();
    for (const e of [...(lineage.data?.edges ?? []), ...extra.edges]) {
      const prev = byId.get(edgeId(e));
      if (!prev || e.depth < prev.depth) byId.set(edgeId(e), e);
    }
    return [...byId.values()].sort((a, b) => a.depth - b.depth || edgeId(a).localeCompare(edgeId(b)));
  }, [lineage.data, extra.edges]);

  const needle = search.trim().toLowerCase();
  const matches = useCallback((r: VersionRef) => !needle
    || `${entityLabel(r.entityType)} ${r.entityType} ${r.entityId}`.toLowerCase().includes(needle), [needle]);
  const edges = useMemo(() => allEdges.filter((e) => shown[e.confidence]
    && (!onlyMatches || !needle || matches(e.source) || matches(e.target))), [allEdges, shown, onlyMatches, needle, matches]);

  const layout = useMemo(() => layoutLineage(edges, direction), [edges, direction]);
  // Large graphs: draw the closest hops first, up to the cap; the table always lists every edge.
  const drawn = useMemo(() => {
    if (layout.nodes.length <= MAX_RENDERED_NODES) return layout;
    const byDistance = [...layout.nodes].sort((a, b) => (direction === 'downstream' ? a.column - b.column : b.column - a.column)
      || a.key.localeCompare(b.key));
    const keep = new Set(byDistance.slice(0, MAX_RENDERED_NODES).map((n) => n.key));
    return { ...layout, nodes: layout.nodes.filter((n) => keep.has(n.key)),
      links: layout.links.filter((l) => keep.has(l.from) && keep.has(l.to)) };
  }, [layout, direction]);
  const refs = useMemo(() => new Map(layout.nodes.map((n) => [n.key, n.ref])), [layout]);

  const select = useCallback((key: string) => { setSelected(key); setDetails(key); }, []);
  const detailRef = details ? refs.get(details) : undefined;

  async function expand(ref: VersionRef) {
    const key = refKey(ref);
    setExpanding(key);
    setExpandError(null);
    try {
      const more: Lineage = await qc.fetchQuery({
        queryKey: keys.scoped(orgId, 'lineage', ref.entityType, ref.entityId, ref.version, direction, 2),
        queryFn: () => provenance.lineage(ref.entityType, ref.entityId, direction, 2, ref.version),
      });
      // Distance of the expanded record from the record on screen, then shift the new hops by it.
      const base = key === rootKey ? 0 : Math.min(...allEdges.filter((e) => refKey(direction === 'downstream' ? e.target : e.source) === key)
        .map((e) => e.depth));
      const shifted = more.edges.map((e) => ({ ...e, depth: (Number.isFinite(base) ? base : 0) + e.depth }));
      setExpanded((x) => ({
        direction, root: rootKey, edges: [...(x.direction === direction && x.root === rootKey ? x.edges : []), ...shifted],
        from: [...(x.direction === direction && x.root === rootKey ? x.from : []), key],
      }));
    } catch (e) {
      setExpandError(e);
    } finally {
      setExpanding(null);
    }
  }

  const hidden = layout.nodes.length - drawn.nodes.length;

  return (
    <Card>
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-border px-4 py-3">
        <div>
          <h3 className="flex items-center gap-2 font-semibold"><Workflow className="h-4 w-4" aria-hidden />Lineage</h3>
          <p className="text-[12px] text-muted">Field-level provenance from v{version}, several hops deep. Only recorded edges are drawn.</p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Direction">
            {(p) => (
              <Select {...p} value={direction} onChange={(e) => { setDirection(e.target.value as 'downstream' | 'upstream'); setSelected(null); }}>
                <option value="downstream">What it fed into</option>
                <option value="upstream">What it came from</option>
              </Select>
            )}
          </Field>
          <Field label="Hops">
            {(p) => (
              <Select {...p} value={String(depth)} onChange={(e) => setDepth(Number(e.target.value))}>
                {[1, 2, 3, 4, 6, 8, 12].map((d) => <option key={d} value={d}>{d}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Find a record">
            {(p) => <Input {...p} value={search} placeholder="type or id" onChange={(e) => setSearch(e.target.value)} />}
          </Field>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2">
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Show edges by confidence">
          {CONFIDENCES.map((c) => (
            <button key={c} type="button" aria-pressed={shown[c]} title={confidenceHelp[c]}
              onClick={() => setShown((s) => ({ ...s, [c]: !s[c] }))}
              className={cn('flex items-center gap-1.5 rounded-md border px-2 py-1 text-[12px]',
                shown[c] ? 'border-border bg-surface-2' : 'border-dashed border-border text-muted opacity-70')}>
              <svg width="26" height="8" aria-hidden><line x1="1" y1="4" x2="25" y2="4" stroke="currentColor" strokeWidth="2"
                strokeDasharray={confidenceDash[c]} /></svg>
              {confidenceLabel[c]}
            </button>
          ))}
          <label className="ml-2 flex items-center gap-1.5 text-[12px] text-muted">
            <input type="checkbox" checked={onlyMatches} disabled={!needle} onChange={(e) => setOnlyMatches(e.target.checked)} />
            Only edges touching matches
          </label>
        </div>
        <Button size="sm" variant={asTable ? 'secondary' : 'ghost'} aria-pressed={asTable} onClick={() => setAsTable((t) => !t)}
          icon={<Table2 className="h-4 w-4" aria-hidden />}>Table</Button>
      </div>

      {lineage.isLoading ? <Spinner /> : lineage.isError ? <ErrorState error={lineage.error} onRetry={() => void lineage.refetch()} /> :
        edges.length === 0 ? (
          <p className="px-4 py-6 text-[13px] text-muted">
            {allEdges.length > 0 ? 'Every edge is hidden by the filters.'
              : direction === 'downstream' ? 'No recorded lineage leads away from this version.'
                : 'No recorded lineage leads into this version.'}
          </p>
        ) : asTable ? (
          <LineageTable edges={edges} selected={selected} onSelect={setSelected} />
        ) : (
          <Suspense fallback={<Spinner />}>
            {hidden > 0 && (
              <p role="status" className="border-b border-border px-4 py-2 text-[12px] text-warning">
                Showing {drawn.nodes.length} of {layout.nodes.length} records (closest hops first). Search, filter by confidence or
                reduce the hops to see others; the table lists every edge.
              </p>
            )}
            <LineageCanvas nodes={drawn.nodes} links={drawn.links} selected={selected} matches={matches} onSelect={select}
              fileName={`lineage-${entityType}-${shortId(entityId)}-v${version}-${direction}`} />
          </Suspense>
        )}
      {lineage.data?.truncated && (
        <p className="border-t border-border px-4 py-2 text-[12px] text-warning">Showing the first 2,000 edges; reduce the hops to see the rest.</p>
      )}
      {extra.from.length > 0 && (
        <p className="border-t border-border px-4 py-2 text-[12px] text-muted">
          Expanded from {extra.from.length} record{extra.from.length === 1 ? '' : 's'} ({extra.edges.length} edges added).
          <Button size="sm" variant="ghost" className="ml-2" onClick={() => setExpanded({ direction: '', root: '', edges: [], from: [] })}>
            Undo expansions
          </Button>
        </p>
      )}

      <Modal open={!!detailRef} onClose={() => setDetails(null)} side
        title={detailRef ? `${entityLabel(detailRef.entityType)} ${shortId(detailRef.entityId)} v${detailRef.version}` : ''}>
        {detailRef && (
          <div className="space-y-4 text-[13px]">
            <NodeDetails node={detailRef} edges={edges} />
            <div className="border-t border-border pt-3">
              <Button size="sm" loading={expanding === refKey(detailRef)} disabled={extra.from.includes(refKey(detailRef))}
                onClick={() => void expand(detailRef)} icon={<GitBranchPlus className="h-4 w-4" aria-hidden />}>
                {extra.from.includes(refKey(detailRef)) ? 'Expanded' : `Expand 2 hops ${direction === 'downstream' ? 'onward' : 'back'}`}
              </Button>
              <p className="mt-1 text-[12px] text-muted">Loads more recorded lineage from this record only, without reloading the rest.</p>
              <InlineError error={expandError} />
            </div>
          </div>
        )}
      </Modal>
    </Card>
  );
}

function NodeDetails({ node, edges }: { node: VersionRef; edges: LineageEdge[] }) {
  const key = refKey(node);
  const incoming = edges.filter((e) => refKey(e.target) === key);
  const outgoing = edges.filter((e) => refKey(e.source) === key);
  return (
    <>
      <Link className="text-accent hover:underline" to={historyPath(node.entityType, node.entityId, node.version)}>
        Open this version's history
      </Link>
      <EdgeGroup title="Computed from" edges={incoming} other="source" />
      <EdgeGroup title="Fed into" edges={outgoing} other="target" />
    </>
  );
}

function EdgeGroup({ title, edges, other }: { title: string; edges: LineageEdge[]; other: 'source' | 'target' }) {
  if (edges.length === 0) return null;
  return (
    <section>
      <h4 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted">{title}</h4>
      <ul className="space-y-2">
        {edges.map((e) => (
          <li key={edgeId(e)} className="rounded-md border border-border p-2.5">
            <p className="font-medium">
              {entityLabel(e[other].entityType)} <span className="font-mono text-[12px]">{shortId(e[other].entityId)}</span> v{e[other].version}
            </p>
            <p className="text-muted">{fieldLabel(e.sourceField)} {e.sourceValue ?? '—'} → {fieldLabel(e.targetField)} {e.targetValue ?? '—'}</p>
            <p className="mt-1 flex flex-wrap items-center gap-1.5">
              <Badge tone={confidenceTone[e.confidence]}>{confidenceLabel[e.confidence]}</Badge>
              <span className="text-[12px] text-muted">{e.relation.toLowerCase().replace(/_/g, ' ')} · {operationLabel(e.operation)} · {e.actorKind} · {dateTime(e.recordedAt)}</span>
            </p>
          </li>
        ))}
      </ul>
    </section>
  );
}

function RefCell({ r, field, value, selected, onSelect }: {
  r: VersionRef; field: string; value: string | null; selected: string | null; onSelect: (k: string) => void;
}) {
  const k = refKey(r);
  const label = `${entityLabel(r.entityType)} ${shortId(r.entityId)} v${r.version}`;
  return (
    <Td>
      <span className="flex items-center gap-1">
        <button type="button" aria-pressed={selected === k} aria-label={`Select ${label} in the graph`} title="Select in the graph"
          onClick={() => onSelect(k)}
          className={cn('rounded p-0.5 text-muted hover:text-accent', selected === k && 'text-accent')}>
          <Crosshair className="h-3.5 w-3.5" aria-hidden />
        </button>
        <Link className="text-accent hover:underline" to={historyPath(r.entityType, r.entityId, r.version)}>{label}</Link>
      </span>
      <span className="block text-[12px] text-muted">{fieldLabel(field)} = {value ?? '—'}</span>
    </Td>
  );
}

function LineageTable({ edges, selected, onSelect }: { edges: LineageEdge[]; selected: string | null; onSelect: (k: string) => void }) {
  return (
    <Table label="Lineage edges">
      <caption className="sr-only">Lineage edges</caption>
      <thead>
        <tr><Th>Hop</Th><Th>From</Th><Th>To</Th><Th>Confidence</Th><Th>Operation</Th></tr>
      </thead>
      <tbody>
        {edges.map((e) => {
          const on = selected !== null && (refKey(e.source) === selected || refKey(e.target) === selected);
          return (
            <tr key={edgeId(e)} className={cn('border-t border-border', on && 'bg-accent/10')} aria-current={on ? 'true' : undefined}>
              <Td>{e.depth}</Td>
              <RefCell r={e.source} field={e.sourceField} value={e.sourceValue} selected={selected} onSelect={onSelect} />
              <RefCell r={e.target} field={e.targetField} value={e.targetValue} selected={selected} onSelect={onSelect} />
              <Td><Badge tone={confidenceTone[e.confidence]}>{confidenceLabel[e.confidence]}</Badge></Td>
              <Td className="text-[12px] text-muted">{operationLabel(e.operation)}</Td>
            </tr>
          );
        })}
      </tbody>
    </Table>
  );
}
