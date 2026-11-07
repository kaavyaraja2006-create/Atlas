import { getNodesBounds, getViewportForBounds, type Node } from '@xyflow/react';

/**
 * Exports what a React Flow graph shows as an SVG or PNG file, in the browser (html-to-image is
 * loaded on demand). The whole graph is framed, not just the visible part; size is capped at
 * 4000 px a side. Shared by the lineage and ER explorers.
 */
export async function exportGraph(container: HTMLElement | null, nodes: Node[], kind: 'svg' | 'png', fileName: string) {
  const { toPng, toSvg } = await import('html-to-image');
  const viewport = container?.querySelector<HTMLElement>('.react-flow__viewport');
  if (!viewport || nodes.length === 0) throw new Error('Nothing to export');
  const bounds = getNodesBounds(nodes);
  const width = Math.min(4000, Math.ceil(bounds.width + 80));
  const height = Math.min(4000, Math.ceil(bounds.height + 80));
  const v = getViewportForBounds(bounds, width, height, 0.1, 2, 0.04);
  const options = {
    backgroundColor: getComputedStyle(document.body).backgroundColor, width, height,
    style: { width: `${width}px`, height: `${height}px`, transform: `translate(${v.x}px, ${v.y}px) scale(${v.zoom})` },
  };
  // html-to-image copies computed styles of HTML elements but not of the SVG edge paths, whose
  // stroke comes from a stylesheet: without this the exported file shows nodes and labels only.
  // Inline the current computed stroke while exporting, then put things back.
  // Only CSSOM is used (never the style attribute), which the strict CSP allows.
  const PROPS = ['stroke', 'stroke-width', 'stroke-dasharray', 'opacity'] as const;
  const paths = [...viewport.querySelectorAll<SVGPathElement>('.react-flow__edge-path')];
  const saved = paths.map((p) => PROPS.map((k) => p.style.getPropertyValue(k)));
  paths.forEach((p) => {
    const c = getComputedStyle(p);
    PROPS.forEach((k) => p.style.setProperty(k, c.getPropertyValue(k)));
  });
  let url: string;
  try {
    url = kind === 'png' ? await toPng(viewport, options) : await toSvg(viewport, options);
  } finally {
    paths.forEach((p, i) => PROPS.forEach((k, j) => {
      if (saved[i]![j]) p.style.setProperty(k, saved[i]![j]!); else p.style.removeProperty(k);
    }));
  }
  const a = document.createElement('a');
  a.href = url;
  a.download = `${fileName}.${kind}`;
  a.click();
}
