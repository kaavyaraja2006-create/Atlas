import {
  BaseEdge, Background, EdgeLabelRenderer, Handle, MarkerType, MiniMap, Position, ReactFlow, ReactFlowProvider,
  getBezierPath, useReactFlow, type Edge, type EdgeProps, type Node, type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import './lineage-canvas.css';
import { Download, Maximize, Minus, Plus } from 'lucide-react';
import { memo, useEffect, useMemo, useState } from 'react';
import type { VersionRef } from '@/api/types';
import { Button, InlineError, Spinner } from '@/components/ui';
import { entityLabel, fieldLabel, shortId } from '@/lib/audit';
import { confidenceDash, confidenceLabel, type LayoutLink, type LayoutNode } from '@/lib/atlasx';
import { cn } from '@/lib/format';
import { exportGraph } from '../graph/exportGraph';
import { NODE_H, NODE_W, placeWithElk, type PositionedLayout } from './layout';

export interface LineageCanvasProps {
  nodes: LayoutNode[];
  links: LayoutLink[];
  selected: string | null;
  matches: (r: VersionRef) => boolean;
  onSelect: (key: string) => void;
  /** Base name of exported files (exports are made in the browser from what is on screen). */
  fileName: string;
}

type NodeData = { ref: VersionRef; root: boolean; dim: boolean; selected: boolean; onSelect: (key: string) => void };
type LinkData = { link: LayoutLink; showLabel: boolean; dim: boolean };

/**
 * React Flow rendering of a lineage layout (lazy-loaded with ELK and html-to-image). Every node
 * and edge comes from the edges the server returned; this component adds none.
 */
export default function LineageCanvas(props: LineageCanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}

const nodeTypes = { version: memo(VersionNode) };
/** Leaves room on the right for the minimap so it never covers a node after fitting. */
const FIT_PADDING = { top: '24px', bottom: '24px', left: '24px', right: '160px' } as const;
const edgeTypes = { lineage: memo(LineageLink) };

function Canvas({ nodes, links, selected, matches, onSelect, fileName }: LineageCanvasProps) {
  const [placed, setPlaced] = useState<PositionedLayout | null>(null);
  const flow = useReactFlow();
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<'svg' | 'png' | null>(null);

  const shape = useMemo(() => nodes.map((n) => `${n.key}@${n.column}`).join('|') + '#' + links.map((l) => `${l.from}>${l.to}`).join('|'),
    [nodes, links]);
  useEffect(() => {
    let live = true;
    void placeWithElk(nodes, links).then((p) => { if (live) setPlaced(p); });
    return () => { live = false; };
  }, [shape]);

  const rfNodes: Node<NodeData>[] = useMemo(() => !placed ? [] : nodes.map((n) => {
    const p = placed.positions.get(n.key) ?? { x: 0, y: 0 };
    return {
      id: n.key, type: 'version', position: { x: p.x, y: p.y }, width: NODE_W, height: NODE_H,
      // Handles declared up front so edges render without a measuring pass.
      handles: [
        { type: 'target', position: Position.Left, x: 0, y: NODE_H / 2 - 4, width: 1, height: 8 },
        { type: 'source', position: Position.Right, x: NODE_W - 1, y: NODE_H / 2 - 4, width: 1, height: 8 },
      ],
      data: { ref: n.ref, root: n.root, dim: !matches(n.ref), selected: selected === n.key, onSelect },
      draggable: false, selectable: false, focusable: false,
    };
  }), [placed, nodes, matches, selected, onSelect]);

  const rfEdges: Edge<LinkData>[] = useMemo(() => links.map((l) => ({
    id: `${l.from}>${l.to}`, source: l.from, target: l.to, type: 'lineage',
    markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
    data: { link: l, showLabel: links.length <= 40, dim: selected !== null && l.from !== selected && l.to !== selected },
    ariaLabel: `${l.edges.length} field mapping(s), ${confidenceLabel[l.confidence]}`,
    focusable: false, selectable: false,
  })), [links, selected]);

  // Fit once the laid-out nodes are on screen (fitView on mount can run before the container has its height).
  useEffect(() => {
    if (!placed) return;
    const t = window.setTimeout(() => void flow.fitView({ padding: FIT_PADDING, maxZoom: 1.1 }), 0);
    return () => window.clearTimeout(t);
  }, [placed, flow]);

  // Keep the selected node in view when the selection comes from the table.
  useEffect(() => {
    if (!selected || !placed) return;
    const p = placed.positions.get(selected);
    if (p) void flow.setCenter(p.x + NODE_W / 2, p.y + NODE_H / 2, { zoom: flow.getZoom(), duration: 200 });
  }, [selected, placed, flow]);

  async function exportImage(kind: 'svg' | 'png') {
    setExportError(null);
    setExporting(kind);
    try {
      await exportGraph(document.querySelector<HTMLElement>('.lineage-canvas'), rfNodes, kind, fileName);
    } catch (e) {
      setExportError(e instanceof Error ? e.message : 'Export failed');
    } finally {
      setExporting(null);
    }
  }

  if (!placed) return <Spinner />;
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2">
        <p className="text-[12px] text-muted">
          {placed.engine === 'elk' ? 'Layered layout (ELK).' : 'ELK layout was unavailable; records are placed in hop columns.'}
          {' '}Drag to pan, scroll or use the buttons to zoom.
        </p>
        <div className="flex items-center gap-1">
          <Button size="sm" variant="ghost" aria-label="Zoom out" onClick={() => void flow.zoomOut({ duration: 150 })}
            icon={<Minus className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" aria-label="Zoom in" onClick={() => void flow.zoomIn({ duration: 150 })}
            icon={<Plus className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" aria-label="Fit the graph" onClick={() => void flow.fitView({ duration: 150, padding: FIT_PADDING, maxZoom: 1.1 })}
            icon={<Maximize className="h-4 w-4" aria-hidden />} />
          <Button size="sm" variant="ghost" loading={exporting === 'svg'} onClick={() => void exportImage('svg')}
            icon={<Download className="h-4 w-4" aria-hidden />}>SVG</Button>
          <Button size="sm" variant="ghost" loading={exporting === 'png'} onClick={() => void exportImage('png')}
            icon={<Download className="h-4 w-4" aria-hidden />}>PNG</Button>
        </div>
      </div>
      <InlineError error={exportError ? new Error(exportError) : null} />
      <div className="lineage-canvas" role="region" aria-label="Lineage graph (scrolls)"
        style={{ height: Math.max(240, Math.min(520, (Math.max(0, ...nodes.map((n) => n.row)) + 1) * (NODE_H + 22) + 150)) }}>
        <ReactFlow nodes={rfNodes} edges={rfEdges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} fitView
          fitViewOptions={{ padding: FIT_PADDING, maxZoom: 1.1 }} minZoom={0.2} maxZoom={2} nodesDraggable={false} nodesConnectable={false}
          nodesFocusable={false} edgesFocusable={false} elementsSelectable={false} panOnScroll={false}
          // A click handler gives nodes pointer events (they would get none when neither selectable nor draggable).
          onNodeClick={(_, n) => onSelect(n.id)}
          proOptions={{ hideAttribution: false }}>
          <Background gap={20} size={1} />
          <MiniMap pannable zoomable ariaLabel="Lineage minimap" style={{ width: 132, height: 84 }} nodeClassName={(n) => (n.data as NodeData).root ? 'minimap-root' : 'minimap-node'} />
        </ReactFlow>
      </div>
    </>
  );
}

function VersionNode({ id, data }: NodeProps<Node<NodeData>>) {
  // Selection happens in ReactFlow's onNodeClick (a keyboard Enter on the button clicks it too).
  return (
    <>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <button type="button" aria-pressed={data.selected} data-key={id}
        className={cn('nopan flex h-full w-full flex-col justify-center rounded-md border bg-surface px-2.5 text-left text-[12px] shadow-sm',
          'hover:border-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent',
          data.root ? 'border-accent ring-1 ring-accent/40' : 'border-border', data.selected && 'ring-2 ring-accent',
          data.dim && 'opacity-35')}>
        <span className="truncate font-medium">{entityLabel(data.ref.entityType)} v{data.ref.version}</span>{' '}
        <span className="truncate font-mono text-[11px] text-muted">{shortId(data.ref.entityId)}</span>
        {data.root && <span className="sr-only"> (this record)</span>}
      </button>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </>
  );
}

function LineageLink({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd, data }: EdgeProps<Edge<LinkData>>) {
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
  const link = data!.link;
  const label = link.edges.length === 1
    ? `${fieldLabel(link.edges[0]!.sourceField)} → ${fieldLabel(link.edges[0]!.targetField)}`
    : `${link.edges.length} fields`;
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd}
        className={cn('lineage-edge', `lineage-edge--${link.confidence.toLowerCase()}`, data!.dim && 'lineage-edge--dim')}
        style={{ strokeDasharray: confidenceDash[link.confidence] }} />
      {data!.showLabel && (
        <EdgeLabelRenderer>
          <div className={cn('lineage-edge-label nodrag nopan', data!.dim && 'opacity-40')}
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}>
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
