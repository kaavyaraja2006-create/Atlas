import {
  Background, BaseEdge, EdgeLabelRenderer, Handle, MarkerType, MiniMap, Position, ReactFlow, ReactFlowProvider,
  getSmoothStepPath, useReactFlow, type Edge, type EdgeProps, type Node, type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import './schema-diagram.css';
import { Download, Maximize, Minus, Plus } from 'lucide-react';
import { memo, useEffect, useMemo, useRef, useState } from 'react';
import type { RelationshipKind, SchemaChange, SchemaModel, SchemaRelationship, SchemaTable } from '@/api/types';
import { Badge, Button, InlineError, Spinner } from '@/components/ui';
import { cn } from '@/lib/format';
import { exportGraph } from '../graph/exportGraph';

export interface SchemaDiagramProps {
  schema: SchemaModel;
  /** Lower-case search text: matching tables are highlighted, others dimmed. */
  search: string;
  kind: RelationshipKind | '';
  /** Changes against a stored version, to mark added and changed tables (empty when not comparing). */
  changes: SchemaChange[];
  onOpenTable: (t: SchemaTable) => void;
  /** Records the export in the platform audit trail; the image is made only if this succeeds. */
  onBeforeExport: (format: 'SVG' | 'PNG', tables: number) => Promise<void>;
}

const W = 236;
const HEADER = 34;
const ROW = 18;
const FOOTER = 24;
const MAX_ROWS = 40;

export const kindShort: Record<RelationshipKind, string> = {
  ONE_TO_ONE: '1 : 1', ONE_TO_MANY: '1 : N', AT_MOST_ONE_ACTIVE: '1 : N, ≤ 1 active', MANY_TO_MANY: 'M : N',
};

type TableData = {
  table: SchemaTable; expanded: boolean; dim: boolean; selected: boolean; change: 'ADDED' | 'CHANGED' | null;
  keyColumns: Set<string>; fkColumns: Set<string>; onToggle: (name: string) => void;
};
type RelData = { r: SchemaRelationship; selected: boolean; dim: boolean };

/**
 * ER diagram drawn from the live catalog the server returned: every table is a node and every
 * relationship an edge, with its kind and evidence. Nothing about the schema is hardcoded.
 * Lazy-loaded with React Flow, ELK and html-to-image.
 */
export default function SchemaDiagram(props: SchemaDiagramProps) {
  return <ReactFlowProvider><Diagram {...props} /></ReactFlowProvider>;
}

const nodeTypes = { table: memo(TableNode) };
const edgeTypes = { relationship: memo(RelationshipEdge) };

function columnsShown(t: SchemaTable, expanded: boolean, keys: Set<string>) {
  const cols = expanded ? t.columns : t.columns.filter((c) => keys.has(c.name));
  return cols.slice(0, MAX_ROWS);
}

function Diagram({ schema, search, kind, changes, onOpenTable, onBeforeExport }: SchemaDiagramProps) {
  const flow = useReactFlow();
  const box = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [relation, setRelation] = useState<SchemaRelationship | null>(null);
  const [focus, setFocus] = useState(false);
  const [inferred, setInferred] = useState(true);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }> | null>(null);
  const [engine, setEngine] = useState<'elk' | 'grid'>('elk');
  const [exporting, setExporting] = useState<'svg' | 'png' | null>(null);
  const [exportError, setExportError] = useState<unknown>(null);

  const keyCols = useMemo(() => new Map(schema.tables.map((t) => {
    const pk = new Set(t.constraints.filter((c) => c.kind === 'PRIMARY_KEY').flatMap((c) => c.columns));
    const fk = new Set(t.constraints.filter((c) => c.kind === 'FOREIGN_KEY').flatMap((c) => c.columns));
    return [t.name, { pk, fk, keys: new Set([...pk, ...fk]) }];
  })), [schema]);

  const rels = useMemo(() => schema.relationships.filter((r) => (inferred || r.evidence !== 'INFERRED')
    && (!kind || r.kind === kind)), [schema, kind, inferred]);
  const neighbours = useMemo(() => {
    if (!focus || !selected) return null;
    const s = new Set([selected]);
    schema.relationships.forEach((r) => { if (r.from === selected) s.add(r.to); if (r.to === selected) s.add(r.from); });
    return s;
  }, [focus, selected, schema]);
  const tables = useMemo(() => schema.tables.filter((t) => !neighbours || neighbours.has(t.name)), [schema, neighbours]);
  const visible = new Set(tables.map((t) => t.name));
  const shownRels = rels.filter((r) => visible.has(r.from) && visible.has(r.to));
  const changeOf = useMemo(() => {
    const m = new Map<string, 'ADDED' | 'CHANGED'>();
    for (const c of changes) {
      if (c.objectType === 'TABLE' && c.change === 'ADDED') m.set(c.name, 'ADDED');
      else if (c.table && !m.has(c.table)) m.set(c.table, 'CHANGED');
    }
    return m;
  }, [changes]);

  const height = (t: SchemaTable) => HEADER + columnsShown(t, expanded.has(t.name), keyCols.get(t.name)!.keys).length * ROW + FOOTER;
  const shape = tables.map((t) => `${t.name}:${height(t)}`).join('|') + '#' + shownRels.map((r) => r.constraint).join('|');
  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        const { default: ELK } = await import('elkjs/lib/elk.bundled.js');
        const g = await new ELK().layout({
          id: 'schema',
          layoutOptions: {
            'elk.algorithm': 'layered', 'elk.direction': 'RIGHT', 'elk.layered.spacing.nodeNodeBetweenLayers': '90',
            'elk.spacing.nodeNode': '30', 'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
          },
          children: tables.map((t) => ({ id: t.name, width: W, height: height(t) })),
          edges: shownRels.filter((r) => r.from !== r.to).map((r) => ({ id: r.constraint, sources: [r.from], targets: [r.to] })),
        });
        if (live) { setPositions(new Map((g.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]))); setEngine('elk'); }
      } catch {
        const cols = Math.ceil(Math.sqrt(tables.length));
        if (live) {
          setPositions(new Map(tables.map((t, i) => [t.name, { x: (i % cols) * (W + 60), y: Math.floor(i / cols) * 260 }])));
          setEngine('grid');
        }
      }
    })();
    return () => { live = false; };
  }, [shape]); // layout depends only on the graph's shape

  useEffect(() => {
    if (!positions) return;
    const t = window.setTimeout(() => void flow.fitView({ padding: 0.06, maxZoom: 1 }), 0);
    return () => window.clearTimeout(t);
  }, [positions, flow]);

  const toggle = (name: string) => setExpanded((s) => { const n = new Set(s); if (n.has(name)) n.delete(name); else n.add(name); return n; });
  const needle = search.trim().toLowerCase();

  const nodes: Node<TableData>[] = useMemo(() => !positions ? [] : tables.map((t) => {
    const p = positions.get(t.name) ?? { x: 0, y: 0 };
    const k = keyCols.get(t.name)!;
    const h = height(t);
    return {
      id: t.name, type: 'table', position: p, width: W, height: h, draggable: false, selectable: false, focusable: false,
      handles: [
        { type: 'source', position: Position.Right, x: W - 1, y: HEADER / 2 - 4, width: 1, height: 8 },
        { type: 'target', position: Position.Left, x: 0, y: HEADER / 2 - 4, width: 1, height: 8 },
      ],
      data: { table: t, expanded: expanded.has(t.name), dim: !!needle && !t.name.includes(needle), selected: selected === t.name,
        change: changeOf.get(t.name) ?? null, keyColumns: k.pk, fkColumns: k.fk, onToggle: toggle },
    };
  }), [positions, tables, expanded, needle, selected, changeOf, keyCols]);

  const edges: Edge<RelData>[] = useMemo(() => shownRels.map((r) => ({
    id: r.constraint, source: r.from, target: r.to, type: 'relationship',
    markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 },
    data: { r, selected: relation?.constraint === r.constraint,
      dim: (!!selected && r.from !== selected && r.to !== selected) || (!!needle && !r.from.includes(needle) && !r.to.includes(needle)) },
    ariaLabel: `${r.from} to ${r.to}: ${kindShort[r.kind]}${r.evidence === 'INFERRED' ? ', inferred' : ''}`,
    focusable: false,
  })), [shownRels, relation, selected, needle]);

  async function doExport(kind: 'svg' | 'png') {
    setExportError(null);
    setExporting(kind);
    try {
      await onBeforeExport(kind === 'svg' ? 'SVG' : 'PNG', tables.length);
      await exportGraph(box.current, nodes, kind, `atlas-schema${focus && selected ? `-${selected}` : ''}`);
    } catch (e) {
      setExportError(e);
    } finally {
      setExporting(null);
    }
  }

  const notValidated = schema.relationships.filter((r) => r.note.includes('NOT VALID')).length;

  if (!positions) return <Spinner />;
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2 text-[12px]">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-1.5">
            <input type="checkbox" checked={inferred} onChange={(e) => setInferred(e.target.checked)} />
            Inferred many-to-many
          </label>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" checked={focus} disabled={!selected} onChange={(e) => setFocus(e.target.checked)} />
            Only {selected ? <span className="font-mono">{selected}</span> : 'the selected table'} and its neighbours
          </label>
          <span className="text-muted">
            {tables.length} of {schema.tables.length} tables · {shownRels.length} relationships
            {engine === 'grid' && ' · ELK layout unavailable, grid used'}
          </span>
        </div>
        <div className="flex items-center gap-1">
          <Button size="sm" variant="ghost" aria-label="Zoom out" onClick={() => void flow.zoomOut({ duration: 150 })}
            icon={<Minus className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" aria-label="Zoom in" onClick={() => void flow.zoomIn({ duration: 150 })}
            icon={<Plus className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" aria-label="Fit the diagram" onClick={() => void flow.fitView({ duration: 150, padding: 0.06 })}
            icon={<Maximize className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" loading={exporting === 'svg'} onClick={() => void doExport('svg')}
            icon={<Download className="h-4 w-4" aria-hidden />}>SVG</Button>
          <Button size="sm" variant="ghost" loading={exporting === 'png'} onClick={() => void doExport('png')}
            icon={<Download className="h-4 w-4" aria-hidden />}>PNG</Button>
        </div>
      </div>
      <InlineError error={exportError} />
      <ul aria-label="Legend" className="flex flex-wrap gap-x-4 gap-y-1 border-b border-border px-4 py-2 text-[12px] text-muted">
        <li><span className="schema-legend schema-legend--declared" aria-hidden /> declared by a foreign key</li>
        <li><span className="schema-legend schema-legend--inferred" aria-hidden /> inferred (junction table)</li>
        <li><span className="font-mono">PK</span> primary key · <span className="font-mono">FK</span> foreign key</li>
        {changes.length > 0 && <li><Badge tone="success">added</Badge> <Badge tone="warning">changed</Badge> since the compared version</li>}
        <li>{notValidated} constraint(s) not validated</li>
      </ul>
      <div ref={box} className="schema-diagram" role="region" aria-label="ER diagram (scrolls)" style={{ height: 620 }}>
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} fitView minZoom={0.05} maxZoom={1.5}
          nodesDraggable={false} nodesConnectable={false} nodesFocusable={false} elementsSelectable={false}
          onNodeClick={(_, n) => setSelected((s) => {
            const next = s === n.id ? null : n.id;
            if (!next) setFocus(false);   // nothing selected: nothing to focus on
            return next;
          })}
          onEdgeClick={(_, e) => setRelation((e.data as RelData).r)}>
          <Background gap={20} size={1} />
          <MiniMap pannable zoomable ariaLabel="ER diagram minimap" style={{ width: 150, height: 100 }}
            nodeClassName={(n) => ((n.data as TableData).selected ? 'minimap-selected' : 'minimap-table')} />
        </ReactFlow>
      </div>
      {(selected || relation) && (
        <div className="grid gap-3 border-t border-border p-4 text-[13px] md:grid-cols-2">
          {selected && (() => {
            const t = schema.tables.find((x) => x.name === selected)!;
            const related = schema.relationships.filter((r) => r.from === selected || r.to === selected);
            return (
              <section aria-label="Selected table">
                <h4 className="mb-1 font-mono font-semibold">{t.name}</h4>
                <p className="text-[12px] text-muted">{t.columns.length} columns · {related.length} relationships</p>
                <ul className="mt-1.5 space-y-0.5 text-[12px]">
                  {related.map((r) => (
                    <li key={r.constraint}>
                      <button type="button" className="text-accent hover:underline" onClick={() => setRelation(r)}>
                        {r.from} → {r.to}
                      </button> <span className="text-muted">{kindShort[r.kind]}</span>
                    </li>
                  ))}
                </ul>
                <Button size="sm" className="mt-2" onClick={() => onOpenTable(t)}>Columns, keys and indexes</Button>
              </section>
            );
          })()}
          {relation && (
            <section aria-label="Selected relationship">
              <h4 className="mb-1 font-semibold">{relation.from} → {relation.to}</h4>
              <p className="flex flex-wrap items-center gap-1.5">
                <Badge>{kindShort[relation.kind]}</Badge>
                {relation.evidence === 'DECLARED' ? <Badge tone="success">Declared</Badge> : <Badge tone="warning">Inferred</Badge>}
              </p>
              <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-[12px]">
                <dt className="text-muted">Constraint</dt><dd className="break-all font-mono">{relation.constraint}</dd>
                <dt className="text-muted">Columns</dt><dd className="font-mono">({relation.fromColumns.join(', ')}) → ({relation.toColumns.join(', ')})</dd>
                {relation.via && (<><dt className="text-muted">Via</dt><dd className="font-mono">{relation.via}</dd></>)}
                <dt className="text-muted">Why</dt><dd>{relation.note}</dd>
              </dl>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

function TableNode({ data }: NodeProps<Node<TableData>>) {
  const { table: t, expanded } = data;
  const keys = new Set([...data.keyColumns, ...data.fkColumns]);
  const cols = columnsShown(t, expanded, keys);
  const hiddenCount = (expanded ? t.columns.length : t.columns.filter((c) => keys.has(c.name)).length) - cols.length;
  const rest = t.columns.length - (expanded ? t.columns.length : t.columns.filter((c) => keys.has(c.name)).length);
  return (
    <div className={cn('schema-table flex h-full w-full flex-col overflow-hidden rounded-md border bg-surface text-[11px] shadow-sm',
      data.selected ? 'border-accent ring-2 ring-accent' : 'border-border', data.dim && 'opacity-30',
      data.change === 'ADDED' && 'schema-table--added', data.change === 'CHANGED' && 'schema-table--changed')}>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <button type="button" data-table={t.name} aria-pressed={data.selected}
        className="nopan flex items-center justify-between gap-2 border-b border-border bg-surface-2 px-2 text-left font-mono text-[12px] font-semibold"
        style={{ height: HEADER }}>
        <span className="truncate">{t.name}</span>
        {t.kind === 'VIEW' && <span className="text-[10px] font-normal text-muted">view</span>}
      </button>
      <ul className="flex-1">
        {cols.map((c) => (
          <li key={c.name} className="flex items-center justify-between gap-2 px-2 font-mono" style={{ height: ROW }}>
            <span className="truncate">
              {data.keyColumns.has(c.name) && <span className="mr-1 text-accent">PK</span>}
              {data.fkColumns.has(c.name) && <span className="mr-1 text-muted">FK</span>}
              {c.name}
            </span>
            <span className="truncate text-muted">{c.type.replace('character varying', 'varchar').replace('timestamp with time zone', 'timestamptz')}</span>
          </li>
        ))}
      </ul>
      <button type="button" className="nopan border-t border-border px-2 text-left text-[11px] text-muted hover:text-accent"
        style={{ height: FOOTER }} onClick={(e) => { e.stopPropagation(); data.onToggle(t.name); }}
        aria-expanded={expanded}>
        {expanded ? 'Key columns only' : rest > 0 ? `+ ${rest} more column${rest === 1 ? '' : 's'}` : 'All columns shown'}
        {hiddenCount > 0 && ` (first ${MAX_ROWS})`}
        <span className="sr-only"> of {t.name}</span>
      </button>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  );
}

function RelationshipEdge({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd, data }: EdgeProps<Edge<RelData>>) {
  const [path, lx, ly] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, borderRadius: 8 });
  const r = data!.r;
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} interactionWidth={14}
        className={cn('schema-edge', r.evidence === 'INFERRED' && 'schema-edge--inferred', data!.selected && 'schema-edge--selected',
          data!.dim && 'schema-edge--dim')} />
      {!data!.dim && (
        <EdgeLabelRenderer>
          <div className="schema-edge-label nodrag nopan" style={{ transform: `translate(-50%, -50%) translate(${lx}px, ${ly}px)` }}>
            {kindShort[r.kind]}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
