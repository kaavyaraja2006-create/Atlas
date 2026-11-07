import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, Network, Table2 } from 'lucide-react';
import { Suspense, lazy, useMemo, useState } from 'react';
import { ApiError } from '@/api/client';
import { keys, schema } from '@/api/endpoints';
import type { RelationshipKind, SchemaDiffView, SchemaRelationship, SchemaSnapshot, SchemaTable } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, ErrorState, Field, Input, Modal, PageHeader, PermissionDenied, Select, Spinner, Table, Td, Th,
} from '@/components/ui';
import { dateTime } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

/** React Flow, ELK and html-to-image load only when the diagram is shown. */
const SchemaDiagram = lazy(() => import('@/components/schema/SchemaDiagram'));

const DENIED = 'The database schema is visible to platform administrators only. Every attempt is recorded.';

export const relationshipLabel: Record<RelationshipKind, string> = {
  ONE_TO_ONE: 'One to one',
  ONE_TO_MANY: 'One to many',
  AT_MOST_ONE_ACTIVE: 'One to many, at most one active',
  MANY_TO_MANY: 'Many to many',
};

/**
 * ER Schema Explorer, first step: the live schema read from PostgreSQL's catalog, its
 * relationships with the evidence for each kind, and comparison with stored versions.
 * The diagram view is a later milestone; everything here is real API data.
 */
export function SchemaPage() {
  const { me } = useSession();
  if (!me) return <Spinner />;
  if (!me.platformAdmin) {
    return (<><PageHeader title="Database schema" /><Card><PermissionDenied body={DENIED} /></Card></>);
  }
  return <SchemaExplorer />;
}

function SchemaExplorer() {
  const live = useQuery({ queryKey: [...keys.me, 'schema', 'live'], queryFn: schema.live, retry: false });
  const [search, setSearch] = useState('');
  const [kind, setKind] = useState<RelationshipKind | ''>('');
  const [open, setOpen] = useState<SchemaTable | null>(null);
  const [view, setView] = useState<'diagram' | 'tables'>('diagram');
  const [from, setFrom] = useState('');
  const versions = useQuery({ queryKey: [...keys.me, 'schema', 'versions'], queryFn: schema.versions, retry: false });
  const diff = useQuery({ queryKey: [...keys.me, 'schema', 'diff', from], queryFn: () => schema.diff(from), enabled: !!from, retry: false });

  const tables = useMemo(() => (live.data?.schema.tables ?? [])
    .filter((t) => t.name.includes(search.trim().toLowerCase())), [live.data, search]);
  const relationships = useMemo(() => (live.data?.schema.relationships ?? []).filter((r) =>
    (!kind || r.kind === kind) && (!search.trim() || r.from.includes(search.trim().toLowerCase())
      || r.to.includes(search.trim().toLowerCase()))), [live.data, kind, search]);

  if (live.isLoading) return <Spinner />;
  if (live.error instanceof ApiError && live.error.isForbidden) {
    return (<><PageHeader title="Database schema" /><Card><PermissionDenied body={DENIED} /></Card></>);
  }
  if (live.isError) return <ErrorState error={live.error} onRetry={() => void live.refetch()} />;
  const d = live.data!;
  const snap = d.latestSnapshot;

  return (
    <>
      <PageHeader title="Database schema"
        description={`Read from PostgreSQL's catalog just now${d.flywayVersion ? ` · migration ${d.flywayVersion}` : ''} · ${d.schema.tables.length} tables and views · ${d.schema.relationships.length} relationships. Viewing is audited.`} />
      <p role="status" className="mb-4 flex items-center gap-2 text-[13px]">
        {d.changedSinceSnapshot ? <AlertTriangle className="h-4 w-4 text-warning" aria-hidden /> : <CheckCircle2 className="h-4 w-4 text-success" aria-hidden />}
        {!snap ? 'No snapshot has been stored yet, so there is nothing to compare with.'
          : d.changedSinceSnapshot ? `The live schema differs from the latest stored snapshot (migration ${snap.flywayVersion}, ${dateTime(snap.capturedAt)}).`
            : `Matches the latest stored snapshot (migration ${snap.flywayVersion}, ${dateTime(snap.capturedAt)}).`}
      </p>
      <div className="mb-4 flex flex-wrap gap-3">
        <Field label="Find a table">{(p) => <Input {...p} value={search} onChange={(e) => setSearch(e.target.value)} placeholder="e.g. order" />}</Field>
        <Field label="Relationship kind">
          {(p) => (
            <Select {...p} value={kind} onChange={(e) => setKind(e.target.value as RelationshipKind | '')}>
              <option value="">Any</option>
              {(Object.keys(relationshipLabel) as RelationshipKind[]).map((k) => <option key={k} value={k}>{relationshipLabel[k]}</option>)}
            </Select>
          )}
        </Field>
      </div>

      <div className="mb-3 flex gap-1" role="group" aria-label="View">
        <Button size="sm" variant={view === 'diagram' ? 'secondary' : 'ghost'} aria-pressed={view === 'diagram'}
          onClick={() => setView('diagram')} icon={<Network className="h-4 w-4" aria-hidden />}>Diagram</Button>
        <Button size="sm" variant={view === 'tables' ? 'secondary' : 'ghost'} aria-pressed={view === 'tables'}
          onClick={() => setView('tables')} icon={<Table2 className="h-4 w-4" aria-hidden />}>Tables</Button>
      </div>

      {view === 'diagram' ? (
        <Card>
          <Suspense fallback={<Spinner />}>
            <SchemaDiagram schema={d.schema} search={search} kind={kind} changes={diff.data?.diff.changes ?? []}
              onOpenTable={setOpen}
              onBeforeExport={async (format, count) => { await schema.recordExport(format, from || 'live', count); }} />
          </Suspense>
        </Card>
      ) : (<>
      <Card>
        <h2 className="border-b border-border px-4 py-3 text-[14px] font-semibold">Tables</h2>
        {tables.length === 0 ? <EmptyState title="No table matches" /> : (
          <Table label="Tables">
            <caption className="sr-only">Tables</caption>
            <thead><tr><Th>Name</Th><Th>Kind</Th><Th align="right">Columns</Th><Th>Primary key</Th><Th align="right">Foreign keys</Th><Th align="right">Indexes</Th><Th align="right">Triggers</Th></tr></thead>
            <tbody>
              {tables.map((t) => (
                <tr key={t.name}>
                  <Td><button type="button" className="font-mono text-[13px] text-accent hover:underline" onClick={() => setOpen(t)}>{t.name}</button></Td>
                  <Td>{t.kind === 'VIEW' ? <Badge tone="info">View</Badge> : 'Table'}</Td>
                  <Td align="right">{t.columns.length}</Td>
                  <Td className="font-mono text-[12px]">{t.constraints.find((c) => c.kind === 'PRIMARY_KEY')?.columns.join(', ') ?? '—'}</Td>
                  <Td align="right">{t.constraints.filter((c) => c.kind === 'FOREIGN_KEY').length}</Td>
                  <Td align="right">{t.indexes.length}</Td>
                  <Td align="right">{t.triggers.length}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Card className="mt-4">
        <h2 className="border-b border-border px-4 py-3 text-[14px] font-semibold">Relationships</h2>
        {relationships.length === 0 ? <EmptyState title="No relationship matches" /> : <RelationshipTable rows={relationships} />}
      </Card>

      </>)}

      <VersionsCard versions={versions} diff={diff} from={from} setFrom={setFrom} />

      <Modal open={!!open} onClose={() => setOpen(null)} side title={open?.name ?? ''}>
        {open && <TableDetails t={open} />}
      </Modal>
    </>
  );
}

function RelationshipTable({ rows }: { rows: SchemaRelationship[] }) {
  return (
    <Table label="Relationships">
      <caption className="sr-only">Relationships</caption>
      <thead><tr><Th>From</Th><Th>To</Th><Th>Kind</Th><Th>Evidence</Th><Th>Why</Th></tr></thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.constraint}>
            <Td className="font-mono text-[12px]">{r.from}<span className="block text-muted">{r.fromColumns.join(', ')}</span></Td>
            <Td className="font-mono text-[12px]">{r.to}<span className="block text-muted">{r.toColumns.join(', ')}</span></Td>
            <Td>{relationshipLabel[r.kind]}{r.via && <span className="block text-[12px] text-muted">via {r.via}</span>}</Td>
            <Td>{r.evidence === 'DECLARED' ? <Badge tone="success">Declared</Badge> : <Badge tone="warning">Inferred</Badge>}</Td>
            <Td className="max-w-md text-[12px] text-muted">{r.note}</Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function TableDetails({ t }: { t: SchemaTable }) {
  return (
    <div className="space-y-4 text-[13px]">
      <section>
        <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Columns</h4>
        <ul className="space-y-0.5 font-mono text-[12px]">
          {t.columns.map((c) => <li key={c.name}>{c.name} <span className="text-muted">{c.type}{c.nullable ? '' : ' not null'}{c.defaultValue ? ` = ${c.defaultValue}` : ''}</span></li>)}
        </ul>
      </section>
      <section>
        <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Constraints</h4>
        <ul className="space-y-1 text-[12px]">
          {t.constraints.map((c) => (
            <li key={c.name}><span className="font-mono">{c.name}</span> <Badge>{c.kind.replace('_', ' ').toLowerCase()}</Badge>
              {c.deferrable && <span className="ml-1 text-muted">deferrable</span>}
              {!c.validated && <span className="ml-1 text-warning">not validated</span>}
              <span className="block break-all font-mono text-muted">{c.definition}</span></li>
          ))}
        </ul>
      </section>
      <section>
        <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Indexes</h4>
        <ul className="space-y-1 text-[12px]">
          {t.indexes.map((i) => <li key={i.name}><span className="font-mono">{i.name}</span>{i.unique && ' (unique)'}{i.predicate && <span className="block text-muted">where {i.predicate}</span>}</li>)}
        </ul>
      </section>
      {t.triggers.length > 0 && (
        <section>
          <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Triggers</h4>
          <ul className="space-y-1 text-[12px]">{t.triggers.map((g) => <li key={g.name}><span className="font-mono">{g.name}</span> <span className="text-muted">{g.timing} {g.events.join(' or ')} → {g.function}()</span></li>)}</ul>
        </section>
      )}
    </div>
  );
}

function VersionsCard({ versions, diff, from, setFrom }: {
  versions: UseQueryResult<SchemaSnapshot[]>; diff: UseQueryResult<SchemaDiffView>; from: string; setFrom: (v: string) => void;
}) {
  return (
    <Card className="mt-4">
      <h2 className="border-b border-border px-4 py-3 text-[14px] font-semibold">Versions</h2>
      {versions.isLoading ? <Spinner /> : versions.isError ? <ErrorState error={versions.error} /> : versions.data!.length === 0 ? (
        <EmptyState title="No stored versions" body="A snapshot is stored when the application starts on a new schema." />
      ) : (
        <div className="space-y-3 p-4">
          <p className="text-[12px] text-muted">Tables added or changed since the chosen version are marked in the diagram.</p>
          <Field label="Compare the live schema with">
            {(p) => (
              <Select {...p} value={from} onChange={(e) => setFrom(e.target.value)}>
                <option value="">Choose a stored version…</option>
                {versions.data!.map((v) => <option key={v.id} value={v.id}>migration {v.flywayVersion} · {dateTime(v.capturedAt)} · {v.sha256.slice(0, 12)}</option>)}
              </Select>
            )}
          </Field>
          {diff.isLoading && <Spinner />}
          {diff.isError && <ErrorState error={diff.error} />}
          {diff.data && (diff.data.diff.changes.length === 0 ? (
            <p role="status" className="text-[13px]">No differences: the live schema matches this version.</p>
          ) : (
            <Table label="Schema changes">
              <caption className="sr-only">Schema changes</caption>
              <thead><tr><Th>Object</Th><Th>Name</Th><Th>Change</Th><Th>Before</Th><Th>After</Th></tr></thead>
              <tbody>
                {diff.data.diff.changes.map((c) => (
                  <tr key={`${c.objectType}:${c.table}:${c.name}`}>
                    <Td>{c.objectType.toLowerCase()}</Td>
                    <Td className="font-mono text-[12px]">{c.table && c.table !== c.name ? `${c.table}.` : ''}{c.name}</Td>
                    <Td><Badge tone={c.change === 'ADDED' ? 'success' : c.change === 'REMOVED' ? 'danger' : 'warning'}>{c.change}</Badge></Td>
                    <Td className="max-w-xs break-all font-mono text-[11px] text-muted">{c.before ?? '—'}</Td>
                    <Td className="max-w-xs break-all font-mono text-[11px] text-muted">{c.after ?? '—'}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ))}
        </div>
      )}
    </Card>
  );
}
