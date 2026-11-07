import type { LayoutLink, LayoutNode } from '@/lib/atlasx';

export const NODE_W = 164;
export const NODE_H = 48;

export interface Positioned {
  key: string;
  x: number;
  y: number;
}

export interface PositionedLayout {
  positions: Map<string, Positioned>;
  /** "elk" normally; "columns" when ELK failed and the deterministic column layout was used. */
  engine: 'elk' | 'columns';
}

/**
 * Places lineage nodes with ELK's layered algorithm. Each node keeps the hop column computed by
 * {@link layoutLineage} as an ELK partition, so the record the user is looking at stays at its
 * edge and every hop is one layer further. ELK is loaded on demand (graph pages only).
 * If ELK fails, the column layout is used instead and the caller is told.
 */
export async function placeWithElk(nodes: LayoutNode[], links: LayoutLink[]): Promise<PositionedLayout> {
  try {
    const { default: ELK } = await import('elkjs/lib/elk.bundled.js');
    const elk = new ELK();
    const graph = await elk.layout({
      id: 'lineage',
      layoutOptions: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.partitioning.activate': 'true',
        'elk.layered.spacing.nodeNodeBetweenLayers': '104',
        'elk.spacing.nodeNode': '22',
        'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
        'elk.layered.crossingMinimization.semiInteractive': 'true',
      },
      children: nodes.map((n) => ({
        id: n.key, width: NODE_W, height: NODE_H,
        layoutOptions: { 'elk.partitioning.partition': String(n.column), 'elk.position': `(0, ${n.row * 10})` },
      })),
      edges: links.map((l, i) => ({ id: `e${i}`, sources: [l.from], targets: [l.to] })),
    });
    const positions = new Map<string, Positioned>();
    for (const c of graph.children ?? []) positions.set(c.id, { key: c.id, x: c.x ?? 0, y: c.y ?? 0 });
    if (positions.size !== nodes.length) throw new Error('ELK dropped nodes');
    return { positions, engine: 'elk' };
  } catch {
    return { positions: columnPositions(nodes), engine: 'columns' };
  }
}

/** The deterministic column layout (hop column × row), used when ELK is unavailable. */
export function columnPositions(nodes: LayoutNode[]): Map<string, Positioned> {
  return new Map(nodes.map((n) => [n.key, { key: n.key, x: n.column * (NODE_W + 104), y: n.row * (NODE_H + 22) }]));
}
