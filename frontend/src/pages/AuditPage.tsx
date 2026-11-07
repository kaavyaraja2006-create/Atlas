import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { History, LifeBuoy, ShieldAlert, ShieldCheck, X } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { audit, keys, recovery, type AuditFilter } from '@/api/endpoints';
import type { AuditEvent } from '@/api/types';
import { DiffView } from '@/components/DiffView';
import {
  Badge, Button, Card, EmptyState, ErrorState, Field, InlineError, Input, Modal, PageHeader, Pagination, Select,
  Spinner, Table, Td, Th,
} from '@/components/ui';
import { ENTITY_TYPES, entityLabel, fieldLabel, historyPath, recordPath, shortId } from '@/lib/audit';
import { dateTime } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

const opTone = { INSERT: 'success', UPDATE: 'info', DELETE: 'danger', COMPENSATE: 'warning' } as const;

/** datetime-local gives local wall time; the API wants an ISO instant. */
function toIso(local: string): string | undefined {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

export function actorText(e: Pick<AuditEvent, 'actorKind' | 'actorName'>): string {
  if (e.actorKind === 'SYSTEM') return 'System';
  if (e.actorKind === 'RECOVERY') return 'Recovery engine';
  return e.actorName ?? 'Unknown user';
}

export function AuditPage() {
  const { current } = useSession();
  const orgId = current?.id ?? null;
  const [page, setPage] = useState(0);
  const [entityType, setEntityType] = useState('');
  const [txnId, setTxnId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [selected, setSelected] = useState<AuditEvent | null>(null);

  const filter: AuditFilter = {
    page, size: 25,
    entityType: entityType || undefined,
    txnId: txnId || undefined,
    from: toIso(from),
    to: toIso(to),
  };
  const list = useQuery({
    queryKey: keys.scoped(orgId, 'audit', filter),
    queryFn: () => audit.list(filter),
    placeholderData: keepPreviousData,
  });
  const verify = useMutation({ mutationFn: audit.verify });
  const filtered = Boolean(entityType || txnId || from || to);
  const reset = () => { setEntityType(''); setTxnId(''); setFrom(''); setTo(''); setPage(0); };

  return (
    <>
      <PageHeader
        title="Audit history"
        description="Every change to every record, newest first. History is append-only and hash-chained."
        actions={
          <Button icon={<ShieldCheck className="h-4 w-4" aria-hidden />} loading={verify.isPending} onClick={() => verify.mutate()}>
            Verify integrity
          </Button>
        }
      />
      {verify.data && (
        verify.data.intact ? (
          <div role="status" className="mb-4 flex items-center gap-2 rounded-sm border border-success/30 bg-success/10 px-3 py-2 text-[13px]">
            <ShieldCheck className="h-4 w-4 text-success" aria-hidden />
            <span>Chain intact: all <span className="num font-medium">{verify.data.eventCount}</span> events verify against their hashes.</span>
          </div>
        ) : (
          <div role="alert" className="mb-4 flex items-center gap-2 rounded-sm border border-danger/30 bg-danger/10 px-3 py-2 text-[13px] text-danger">
            <ShieldAlert className="h-4 w-4" aria-hidden />
            <span>History has been altered: the chain breaks at event <span className="num font-semibold">#{verify.data.firstBrokenIndex}</span>. Treat later history as untrusted.</span>
          </div>
        )
      )}
      <div className="mb-4"><InlineError error={verify.error} /></div>

      <Card>
        <div className="grid gap-3 border-b border-border p-3 sm:grid-cols-2 lg:grid-cols-[12rem_1fr_1fr_auto]">
          <Select aria-label="Record type" value={entityType} onChange={(e) => { setEntityType(e.target.value); setPage(0); }}>
            <option value="">All record types</option>
            {ENTITY_TYPES.map((t) => <option key={t} value={t}>{entityLabel(t)}</option>)}
          </Select>
          <Input aria-label="From" type="datetime-local" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          <Input aria-label="To" type="datetime-local" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
          {filtered && <Button variant="ghost" icon={<X className="h-4 w-4" aria-hidden />} onClick={reset}>Clear filters</Button>}
        </div>
        {txnId && (
          <div className="flex items-center gap-2 border-b border-border bg-surface-2 px-4 py-2 text-[13px]">
            Showing one transaction <span className="font-mono text-[12px]">{shortId(txnId)}</span>
            <button type="button" className="text-accent hover:underline" onClick={() => { setTxnId(''); setPage(0); }}>show all</button>
          </div>
        )}
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? (
            <EmptyState title={filtered ? 'No changes match these filters' : 'No history yet'} />
          ) : list.data && (
            <>
              <Table>
                <thead>
                  <tr><Th>#</Th><Th>When</Th><Th>Who</Th><Th>Change</Th><Th>Record</Th><Th>Fields</Th></tr>
                </thead>
                <tbody>
                  {list.data.items.map((e) => (
                    <tr key={e.id} className="cursor-pointer hover:bg-surface-2" onClick={() => setSelected(e)}>
                      <Td className="num text-muted">{e.chainIndex}</Td>
                      <Td className="whitespace-nowrap">{dateTime(e.occurredAt)}</Td>
                      <Td className="whitespace-nowrap">{actorText(e)}</Td>
                      <Td><Badge tone={opTone[e.operation]}>{e.operation}</Badge></Td>
                      <Td className="whitespace-nowrap">
                        <button type="button" className="text-left hover:underline" onClick={(ev) => { ev.stopPropagation(); setSelected(e); }}>
                          {entityLabel(e.entityType)} <span className="font-mono text-[12px] text-muted">{shortId(e.entityId)}</span>
                          <span className="text-muted"> v{e.entityVersion}</span>
                        </button>
                      </Td>
                      <Td className="text-muted">{e.changedFields.map(fieldLabel).join(', ')}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
      {selected && (
        <EventDrawer event={selected} onClose={() => setSelected(null)}
          onShowTransaction={(id) => { setTxnId(id); setEntityType(''); setPage(0); setSelected(null); }} />
      )}
    </>
  );
}

function EventDrawer({ event: e, onClose, onShowTransaction }: {
  event: AuditEvent; onClose: () => void; onShowTransaction: (txnId: string) => void;
}) {
  const { can } = useSession();
  const open = recordPath(e.entityType, e.entityId, e.after ?? e.before);
  return (
    <Modal open side onClose={onClose} title={`${entityLabel(e.entityType)} · v${e.entityVersion}`}>
      <div className="space-y-5">
        <dl className="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-1.5 text-[13px]">
          <dt className="text-muted">Change</dt><dd><Badge tone={opTone[e.operation]}>{e.operation}</Badge></dd>
          <dt className="text-muted">When</dt><dd>{dateTime(e.occurredAt)}</dd>
          <dt className="text-muted">Who</dt><dd>{actorText(e)}</dd>
          <dt className="text-muted">Reason</dt><dd>{e.reason ?? '—'}</dd>
          <dt className="text-muted">Record id</dt><dd className="break-all font-mono text-[12px]">{e.entityId}</dd>
          <dt className="text-muted">Chain</dt><dd className="font-mono text-[12px]">#{e.chainIndex} · {e.hash.slice(0, 16)}…</dd>
          {e.correlationId && (<><dt className="text-muted">Request</dt><dd className="break-all font-mono text-[12px]">{e.correlationId}</dd></>)}
        </dl>
        <section>
          <h3 className="mb-2 text-[13px] font-semibold">Fields</h3>
          <DiffView before={e.before} after={e.after} changedFields={e.changedFields} />
        </section>
        <div className="flex flex-wrap gap-2">
          <Link to={historyPath(e.entityType, e.entityId, e.entityVersion)}>
            <Button size="sm" icon={<History className="h-3.5 w-3.5" aria-hidden />}>Record history</Button>
          </Link>
          <Button size="sm" onClick={() => onShowTransaction(e.txnId)}>Everything in this transaction</Button>
          {open && <Link to={open}><Button size="sm" variant="ghost">Open record</Button></Link>}
        </div>
        {e.operation === 'UPDATE' && can(Perm.recoveryCreate) && <OpenIncident event={e} />}
      </div>
    </Modal>
  );
}

/** Declares this change faulty. Opening an incident changes no business data. */
function OpenIncident({ event }: { event: AuditEvent }) {
  const navigate = useNavigate();
  const [title, setTitle] = useState(`Faulty ${entityLabel(event.entityType).toLowerCase()} change: ${event.changedFields.map(fieldLabel).join(', ')}`.slice(0, 200));
  const [reason, setReason] = useState('');
  const [expanded, setExpanded] = useState(false);
  const open = useMutation({
    mutationFn: () => recovery.open({ changeEventId: event.id, title: title.trim(), reason: reason.trim() }),
    onSuccess: (incident) => navigate(`/recovery/${incident.id}`),
  });
  if (!expanded) {
    return (
      <section className="border-t border-border pt-4">
        <Button size="sm" variant="danger" icon={<LifeBuoy className="h-3.5 w-3.5" aria-hidden />} onClick={() => setExpanded(true)}>
          Open recovery incident
        </Button>
        <p className="mt-1.5 text-[12px] text-muted">If this change was a mistake, PASCAR can find everything that used it.</p>
      </section>
    );
  }
  return (
    <section className="space-y-3 border-t border-border pt-4">
      <h3 className="text-[13px] font-semibold">Open recovery incident</h3>
      <Field label="Title">{(p) => <Input {...p} value={title} maxLength={200} onChange={(ev) => setTitle(ev.target.value)} />}</Field>
      <Field label="What was wrong">{(p) => <Input {...p} value={reason} maxLength={1000} onChange={(ev) => setReason(ev.target.value)} />}</Field>
      <InlineError error={open.error} />
      <div className="flex gap-2">
        <Button size="sm" onClick={() => setExpanded(false)}>Cancel</Button>
        <Button size="sm" variant="primary" disabled={!title.trim() || !reason.trim()} loading={open.isPending}
          onClick={() => open.mutate()}>Open incident</Button>
      </div>
    </section>
  );
}
