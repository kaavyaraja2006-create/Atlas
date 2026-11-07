import type { Snapshot } from '@/api/types';
import { diffRows, fieldLabel, formatValue } from '@/lib/audit';
import { cn } from '@/lib/format';

/** Field-level before/after. Changed fields are highlighted and listed first. */
export function DiffView({ before, after, changedFields }: {
  before: Snapshot | null; after: Snapshot | null; changedFields: string[];
}) {
  const rows = diffRows(before, after, changedFields);
  if (rows.length === 0) return <p className="text-muted">No recorded fields.</p>;
  return (
    <table className="w-full border-collapse text-table">
      <thead>
        <tr className="text-[11px] uppercase tracking-wide text-muted">
          <th scope="col" className="w-1/3 border-b border-border py-1.5 text-left font-semibold">Field</th>
          <th scope="col" className="border-b border-border py-1.5 text-left font-semibold">Before</th>
          <th scope="col" className="border-b border-border py-1.5 text-left font-semibold">After</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.field} className={cn(row.changed && 'bg-warning/10')}>
            <td className="border-b border-border py-1.5 pr-2 align-top">
              <span className={cn(row.changed ? 'font-medium text-text' : 'text-muted')}>{fieldLabel(row.field)}</span>
              {row.changed && <span className="sr-only"> (changed)</span>}
            </td>
            <td className={cn('num border-b border-border py-1.5 pr-2 align-top break-all', row.changed ? 'text-danger line-through decoration-danger/50' : 'text-muted')}>
              {formatValue(row.before)}
            </td>
            <td className={cn('num border-b border-border py-1.5 align-top break-all', row.changed ? 'font-medium text-success' : 'text-muted')}>
              {formatValue(row.after)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
