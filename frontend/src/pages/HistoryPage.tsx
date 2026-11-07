import { useQuery } from '@tanstack/react-query';
import { ArrowDownRight, ArrowLeft, ArrowUpLeft } from 'lucide-react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { audit, history, keys, provenance } from '@/api/endpoints';
import type { AuditEvent, ProvenanceEdge } from '@/api/types';
import { DiffView } from '@/components/DiffView';
import { LineageExplorer } from '@/components/LineageExplorer';
import { Badge, Button, Card, EmptyState, ErrorState, Spinner, Table, Td, Th } from '@/components/ui';
import { edgeSentence, entityLabel, fieldLabel, historyPath, recordPath, shortId } from '@/lib/audit';
import { cn, dateTime } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';
import { actorText } from './AuditPage';

/**
 * One record's versions, oldest first. For the selected version it shows the
 * recorded provenance: what this version's values were computed from, and
 * which later records consumed them. Every link opens that record's history
 * at the exact version involved.
 */
export function HistoryPage() {
  const { entityType = '', entityId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const { current } = useSession();
  const orgId = current?.id ?? null;

  const events = useQuery({
    queryKey: keys.scoped(orgId, 'history', entityType, entityId),
    queryFn: () => audit.list({ entityType, entityId, size: 100 }),
  });
  const versions = [...(events.data?.items ?? [])].sort((a, b) => a.entityVersion - b.entityVersion);
  const requested = params.get('version');
  const selected =
    versions.find((v) => String(v.entityVersion) === requested) ?? versions[versions.length - 1] ?? null;

  if (events.isLoading) return <Spinner />;
  if (events.isError) return <ErrorState error={events.error} onRetry={() => void events.refetch()} />;
  if (!selected) {
    return <EmptyState title="No history for this record" body="It may belong to another organization, or the link is wrong." />;
  }
  const open = recordPath(entityType, entityId, selected.after ?? selected.before);

  return (
    <>
      <Link to="/audit" className="mb-3 inline-flex items-center gap-1 text-[13px] text-muted hover:text-text">
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> Audit history
      </Link>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{entityLabel(entityType)} history</h1>
          <p className="mt-1 font-mono text-[12px] text-muted">{entityId}</p>
        </div>
        {open && <Link to={open}><Button size="sm">Open record</Button></Link>}
      </div>

      <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
        <Card>
          <h2 className="border-b border-border px-4 py-2.5 text-[11px] font-semibold uppercase tracking-wide text-muted">
            Versions
          </h2>
          <ol aria-label="Versions">
            {versions.map((v) => (
              <li key={v.id}>
                <button type="button" aria-current={v.id === selected.id ? 'true' : undefined}
                  onClick={() => setParams({ version: String(v.entityVersion) }, { replace: true })}
                  className={cn('w-full border-b border-border px-4 py-2.5 text-left text-[13px] hover:bg-surface-2',
                    v.id === selected.id && 'bg-accent/10 shadow-[inset_3px_0_0_rgb(var(--accent))]')}>
                  <span className="flex items-center justify-between gap-2">
                    <span className="font-medium">v{v.entityVersion}</span>
                    <span className="text-[11px] text-muted">{v.operation}</span>
                  </span>
                  <span className="block text-[12px] text-muted">{dateTime(v.occurredAt)} · {actorText(v)}</span>
                  {v.operation === 'UPDATE' && (
                    <span className="block truncate text-[12px] text-muted">{v.changedFields.map(fieldLabel).join(', ')}</span>
                  )}
                </button>
              </li>
            ))}
          </ol>
        </Card>

        <div className="min-w-0 space-y-4">
          <Card className="p-4">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <h2 className="font-semibold">Version {selected.entityVersion}</h2>
              <Badge tone={selected.operation === 'INSERT' ? 'success' : 'info'}>{selected.operation}</Badge>
              <span className="text-[13px] text-muted">{selected.reason ?? ''}</span>
            </div>
            <DiffView before={selected.before} after={selected.after} changedFields={selected.changedFields} />
          </Card>
          <Lineage event={selected} />
          <LineageExplorer entityType={entityType} entityId={entityId} version={selected.entityVersion} />
          <FieldHistory entityType={entityType} entityId={entityId} />
        </div>
      </div>
    </>
  );
}

function Lineage({ event }: { event: AuditEvent }) {
  const { current } = useSession();
  const orgId = current?.id ?? null;
  const up = useQuery({
    queryKey: keys.scoped(orgId, 'provenance', event.entityType, event.entityId, event.entityVersion, 'up'),
    queryFn: () => provenance.edges(event.entityType, event.entityId, 'upstream', event.entityVersion),
  });
  const down = useQuery({
    queryKey: keys.scoped(orgId, 'provenance', event.entityType, event.entityId, event.entityVersion, 'down'),
    queryFn: () => provenance.edges(event.entityType, event.entityId, 'downstream', event.entityVersion),
  });
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <EdgeList title="Computed from" icon={<ArrowUpLeft className="h-4 w-4" aria-hidden />} query={up} side="source"
        empty="No recorded inputs: this version's values were entered, not derived." />
      <EdgeList title="Fed into" icon={<ArrowDownRight className="h-4 w-4" aria-hidden />} query={down} side="target"
        empty="Nothing has been derived from this version." />
    </div>
  );
}

function EdgeList({ title, icon, query, side, empty }: {
  title: string; icon: React.ReactNode; side: 'source' | 'target'; empty: string;
  query: { data?: ProvenanceEdge[] | undefined; isLoading: boolean; isError: boolean; error: unknown };
}) {
  return (
    <Card>
      <h3 className="flex items-center gap-2 border-b border-border px-4 py-2.5 text-[13px] font-semibold">{icon}{title}</h3>
      {query.isLoading ? <Spinner /> : query.isError ? <ErrorState error={query.error} /> :
        (query.data ?? []).length === 0 ? <p className="px-4 py-4 text-[13px] text-muted">{empty}</p> : (
          <ul className="divide-y divide-border">
            {(query.data ?? []).map((edge) => {
              const other = edge[side];
              return (
                <li key={`${other.entityType}-${other.entityId}-${other.version}-${edge.relation}`} className="px-4 py-2.5 text-[13px]">
                  <Link to={historyPath(other.entityType, other.entityId, other.version)} className="font-medium text-accent hover:underline">
                    {entityLabel(other.entityType)} <span className="font-mono text-[12px]">{shortId(other.entityId)}</span> v{other.version}
                  </Link>
                  <p className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[12px] text-muted">
                    {edgeSentence(edge, side === 'source' ? 'upstream' : 'downstream')}
                    {edge.evidence === 'BACKFILLED' && <Badge tone="warning">backfilled</Badge>}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
    </Card>
  );
}

/** Every field change of the record, newest first (ATLAS X): from the field-history view of the audit log. */
function FieldHistory({ entityType, entityId }: { entityType: string; entityId: string }) {
  const { current } = useSession();
  const orgId = current?.id ?? null;
  const fields = useQuery({
    queryKey: keys.scoped(orgId, 'field-history', entityType, entityId),
    queryFn: () => history.fields(entityType, entityId),
  });
  const rows = [...(fields.data ?? [])].reverse();
  return (
    <Card>
      <h3 className="border-b border-border px-4 py-2.5 text-[13px] font-semibold">Field history</h3>
      {fields.isLoading ? <Spinner /> : fields.isError ? <ErrorState error={fields.error} /> : rows.length === 0 ? (
        <p className="px-4 py-4 text-[13px] text-muted">No field changes recorded.</p>
      ) : (
        <Table label="Field history">
          <thead>
            <tr><Th>Version</Th><Th>Field</Th><Th>Before</Th><Th>After</Th><Th>By</Th><Th>When</Th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.newVersion}-${r.field}`} className="border-t border-border">
                <Td>v{r.newVersion}</Td>
                <Td>{fieldLabel(r.field)}</Td>
                <Td className="font-mono text-[12px] text-muted">{r.previousValue ?? '—'}</Td>
                <Td className="font-mono text-[12px]">{r.newValue ?? '—'}</Td>
                <Td className="text-[12px]">{r.actorName ?? r.actorKind}{r.reason ? <span className="block text-muted">{r.reason}</span> : null}</Td>
                <Td className="text-[12px] text-muted">{dateTime(r.occurredAt)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
