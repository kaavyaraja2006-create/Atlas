import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Plus, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { integrity, keys } from '@/api/endpoints';
import type {
  IntegrityIncident, IntegrityRule, IntegritySeverity, IntegrityStatus,
} from '@/api/types';
import {
  Badge, Button, Card, EmptyState, ErrorState, Field, InlineError, Input, Modal, PageHeader, Pagination, Select, Spinner,
  Table, Td, Th,
} from '@/components/ui';
import { entityLabel, historyPath, shortId } from '@/lib/audit';
import { hasHistory, integrityStatusTone, riskTone } from '@/lib/atlasx';
import { dateTime } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

const STATUSES: IntegrityStatus[] = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLEARED'];
const SEVERITIES: IntegritySeverity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/**
 * Continuous integrity monitoring: rules that check the data, the runs that evaluated
 * them, and the incidents they raised. Checks only read business data; nothing here
 * changes an order or invoice. A finding is fixed through a recovery incident or a
 * normal edit, after which the next check clears it.
 */
export function IntegrityPage() {
  const { can } = useSession();
  const manage = can(Perm.integrityManage);
  return (
    <>
      <PageHeader title="Integrity"
        description="Rules that check the data on a schedule. Checks only read; fixes go through recovery or normal edits."
        actions={manage ? <RunChecksButton /> : undefined} />
      <IncidentsCard />
      <RulesCard manage={manage} />
      <RunsCard />
    </>
  );
}

function useInvalidateIntegrity() {
  const { current } = useSession();
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: keys.scoped(current?.id ?? null, 'integrity') });
}

function RunChecksButton() {
  const invalidate = useInvalidateIntegrity();
  const [message, setMessage] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: integrity.check,
    onSuccess: async (r) => {
      setMessage(r.status === 'FAILED'
        ? `Check failed: ${r.failures.join('; ')}`
        : `${r.rulesEvaluated} rule${r.rulesEvaluated === 1 ? '' : 's'} evaluated: ${r.findings} finding(s), ${r.opened} new, ${r.cleared} cleared.`);
      await invalidate();
    },
  });
  return (
    <div className="flex flex-col items-end gap-1">
      <Button variant="primary" loading={run.isPending} onClick={() => run.mutate()}
        icon={<Activity className="h-4 w-4" aria-hidden />}>Run checks now</Button>
      <InlineError error={run.error} />
      {message && <p role="status" className="text-[12px] text-muted">{message}</p>}
    </div>
  );
}

/* ---------------------------------------------------------------- incidents */

function IncidentsCard() {
  const { current } = useSession();
  const [status, setStatus] = useState<IntegrityStatus | ''>('OPEN');
  const [severity, setSeverity] = useState<IntegritySeverity | ''>('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const list = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'integrity', 'incidents', status, severity, page),
    queryFn: () => integrity.incidents({ page, size: 20, status: status || undefined, severity: severity || undefined }),
  });
  return (
    <Card>
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-border px-4 py-3">
        <h2 className="text-[14px] font-semibold">Findings</h2>
        <div className="flex gap-3">
          <Field label="Status">
            {(p) => (
              <Select {...p} value={status} onChange={(e) => { setStatus(e.target.value as IntegrityStatus | ''); setPage(0); }}>
                <option value="">Any</option>
                {STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Severity">
            {(p) => (
              <Select {...p} value={severity} onChange={(e) => { setSeverity(e.target.value as IntegritySeverity | ''); setPage(0); }}>
                <option value="">Any</option>
                {SEVERITIES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
              </Select>
            )}
          </Field>
        </div>
      </div>
      {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        : list.data!.items.length === 0 ? (
          <EmptyState title="No findings" body={status === 'OPEN' ? 'Nothing open. Run the checks to look again.' : 'Nothing matches these filters.'} />
        ) : (
          <>
            <Table label="Findings">
              <caption className="sr-only">Findings</caption>
              <thead><tr><Th>Finding</Th><Th>Record</Th><Th>Severity</Th><Th>Status</Th><Th align="right">Seen</Th><Th>Last seen</Th></tr></thead>
              <tbody>
                {list.data!.items.map((i) => (
                  <tr key={i.id}>
                    <Td>
                      <button type="button" className="text-left text-accent hover:underline" onClick={() => setSelected(i.id)}>{i.title}</button>
                      <span className="block text-[12px] text-muted">{i.ruleName} (rule v{i.ruleVersion})</span>
                    </Td>
                    <Td><SubjectLink type={i.subjectType} id={i.subjectId} /></Td>
                    <Td><Badge tone={riskTone[i.severity]}>{i.severity}</Badge></Td>
                    <Td><Badge tone={integrityStatusTone[i.status]}>{i.status}</Badge></Td>
                    <Td align="right">{i.occurrences}×</Td>
                    <Td className="whitespace-nowrap text-muted">{dateTime(i.lastSeenAt)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={page} totalPages={list.data!.page.totalPages} totalItems={list.data!.page.totalItems} onPage={setPage} />
          </>
        )}
      <IncidentDialog id={selected} onClose={() => setSelected(null)} />
    </Card>
  );
}

function SubjectLink({ type, id }: { type: string; id: string }) {
  const label = <>{entityLabel(type)} <span className="font-mono text-[12px]">{shortId(id)}</span></>;
  return hasHistory(type)
    ? <Link className="text-accent hover:underline" to={historyPath(type, id)}>{label}</Link>
    : <span>{label}</span>;
}

function IncidentDialog({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { current, can } = useSession();
  const invalidate = useInvalidateIntegrity();
  const [note, setNote] = useState('');
  const detail = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'integrity', 'incident', id),
    queryFn: () => integrity.incident(id!),
    enabled: !!id,
  });
  const move = useMutation({
    mutationFn: ({ i, to }: { i: IntegrityIncident; to: 'ACKNOWLEDGED' | 'RESOLVED' }) => integrity.transition(i, to, note),
    onSuccess: async () => { setNote(''); await invalidate(); },
  });
  const i = detail.data?.incident;
  const live = i && (i.status === 'OPEN' || i.status === 'ACKNOWLEDGED');
  return (
    <Modal open={!!id} onClose={onClose} side title={i?.title ?? 'Finding'}>
      {detail.isLoading ? <Spinner /> : detail.isError ? <ErrorState error={detail.error} /> : i && (
        <div className="space-y-4 text-[13px]">
          <p className="flex flex-wrap gap-2">
            <Badge tone={riskTone[i.severity]}>{i.severity}</Badge>
            <Badge tone={integrityStatusTone[i.status]}>{i.status}</Badge>
            <span className="text-muted">first seen {dateTime(i.firstSeenAt)} · seen {i.occurrences}×</span>
          </p>
          <p className="[&_a]:underline"><SubjectLink type={i.subjectType} id={i.subjectId} />
            {hasHistory(i.subjectType) && <span className="text-muted"> — open its history to find the change that caused this.</span>}</p>
          <section>
            <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">What the check found</h4>
            <pre className="overflow-x-auto rounded-md bg-surface-2 p-2 font-mono text-[12px]">{JSON.stringify(i.detail, null, 2)}</pre>
          </section>
          {live && can(Perm.integrityManage) && (
            <section className="space-y-2">
              <Field label="Note" hint="Recorded in the finding's history.">
                {(p) => <Input {...p} value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />}
              </Field>
              <div className="flex gap-2">
                {i.status === 'OPEN' && (
                  <Button loading={move.isPending && move.variables?.to === 'ACKNOWLEDGED'} disabled={note.trim().length < 3}
                    onClick={() => move.mutate({ i, to: 'ACKNOWLEDGED' })}>Acknowledge</Button>
                )}
                <Button variant="primary" loading={move.isPending && move.variables?.to === 'RESOLVED'} disabled={note.trim().length < 3}
                  onClick={() => move.mutate({ i, to: 'RESOLVED' })}>Mark resolved</Button>
              </div>
              <p className="text-[12px] text-muted">Resolving records a decision; it does not change the data. If the problem is still there, the next check opens a new finding.</p>
              <InlineError error={move.error} />
            </section>
          )}
          <section>
            <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">History</h4>
            <ol className="space-y-1.5">
              {detail.data!.history.map((h, n) => (
                <li key={n} className="border-l-2 border-border pl-2">
                  <span className="font-medium">{h.fromStatus ? `${h.fromStatus} → ${h.toStatus}` : h.toStatus}</span>
                  <span className="text-muted"> · {h.actorName ?? (h.runId ? 'integrity check' : 'system')} · {dateTime(h.occurredAt)}</span>
                  {h.note && <span className="block text-[12px] text-muted">“{h.note}”</span>}
                </li>
              ))}
            </ol>
          </section>
        </div>
      )}
    </Modal>
  );
}

/* ---------------------------------------------------------------- rules */

function RulesCard({ manage }: { manage: boolean }) {
  const { current } = useSession();
  const invalidate = useInvalidateIntegrity();
  const orgId = current?.id ?? null;
  const rules = useQuery({ queryKey: keys.scoped(orgId, 'integrity', 'rules'), queryFn: integrity.rules });
  const kinds = useQuery({ queryKey: keys.scoped(orgId, 'integrity', 'kinds'), queryFn: integrity.kinds, enabled: manage });
  const [versionsOf, setVersionsOf] = useState<IntegrityRule | null>(null);
  const add = useMutation({ mutationFn: integrity.createRule, onSuccess: invalidate });
  const toggle = useMutation({
    mutationFn: (r: IntegrityRule) => integrity.updateRule(r, { enabled: !r.enabled }),
    onSuccess: invalidate,
  });
  const used = new Set(rules.data?.map((r) => r.kind));
  const available = (kinds.data ?? []).filter((k) => !used.has(k.kind));
  return (
    <Card className="mt-4">
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <ShieldCheck className="h-4 w-4 text-muted" aria-hidden />
        <h2 className="text-[14px] font-semibold">Rules</h2>
      </div>
      {rules.isLoading ? <Spinner /> : rules.isError ? <ErrorState error={rules.error} onRetry={() => void rules.refetch()} /> : (
        <>
          {rules.data!.length === 0 ? (
            <EmptyState title="No rules yet" body={manage ? 'Add one of the checks below to start monitoring.' : 'An administrator has not set up any checks.'} />
          ) : (
            <Table label="Rules">
              <caption className="sr-only">Rules</caption>
              <thead><tr><Th>Rule</Th><Th>Severity</Th><Th>State</Th><Th>Changed</Th><Th /></tr></thead>
              <tbody>
                {rules.data!.map((r) => (
                  <tr key={r.id}>
                    <Td><span className="font-medium">{r.name}</span><span className="block font-mono text-[12px] text-muted">{r.code}</span></Td>
                    <Td><Badge tone={riskTone[r.severity]}>{r.severity}</Badge></Td>
                    <Td>{r.enabled ? <Badge tone="success">Enabled</Badge> : <Badge>Disabled</Badge>}</Td>
                    <Td className="whitespace-nowrap text-muted">{dateTime(r.updatedAt)} · v{r.version}</Td>
                    <Td align="right">
                      <div className="flex justify-end gap-2">
                        <Button size="sm" variant="ghost" onClick={() => setVersionsOf(r)}>Versions</Button>
                        {manage && (
                          <Button size="sm" loading={toggle.isPending && toggle.variables?.id === r.id} onClick={() => toggle.mutate(r)}>
                            {r.enabled ? 'Disable' : 'Enable'}
                          </Button>
                        )}
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
          <InlineError error={toggle.error ?? add.error} />
          {manage && available.length > 0 && (
            <ul className="grid gap-2 p-4 md:grid-cols-2" aria-label="Checks you can add">
              {available.map((k) => (
                <li key={k.kind} className="flex items-start justify-between gap-3 rounded-md border border-border p-3 text-[13px]">
                  <span><span className="font-medium">{k.name}</span><span className="block text-[12px] text-muted">{k.description}</span></span>
                  <Button size="sm" loading={add.isPending && add.variables === k.kind} onClick={() => add.mutate(k.kind)}
                    icon={<Plus className="h-4 w-4" aria-hidden />} aria-label={`Add ${k.name}`}>Add</Button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <VersionsDialog rule={versionsOf} onClose={() => setVersionsOf(null)} />
    </Card>
  );
}

function VersionsDialog({ rule, onClose }: { rule: IntegrityRule | null; onClose: () => void }) {
  const { current } = useSession();
  const versions = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'integrity', 'versions', rule?.id, rule?.version),
    queryFn: () => integrity.versions(rule!.id),
    enabled: !!rule,
  });
  return (
    <Modal open={!!rule} onClose={onClose} title={rule ? `${rule.name}: versions` : ''}>
      {versions.isLoading ? <Spinner /> : versions.isError ? <ErrorState error={versions.error} /> : (
        <ol className="space-y-2 text-[13px]">
          {versions.data?.map((v) => (
            <li key={v.version} className="rounded-md border border-border p-2">
              <span className="font-medium">v{v.version}</span> · {v.severity} · {v.enabled ? 'enabled' : 'disabled'}
              <span className="block text-[12px] text-muted">{v.changedByName ?? 'someone'} · {dateTime(v.changedAt)}</span>
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}

/* ---------------------------------------------------------------- runs */

function RunsCard() {
  const { current } = useSession();
  const runs = useQuery({ queryKey: keys.scoped(current?.id ?? null, 'integrity', 'runs'), queryFn: integrity.checks });
  return (
    <Card className="mt-4">
      <h2 className="border-b border-border px-4 py-3 text-[14px] font-semibold">Recent checks</h2>
      {runs.isLoading ? <Spinner /> : runs.isError ? <ErrorState error={runs.error} /> : runs.data!.length === 0 ? (
        <EmptyState title="No checks have run" body="Checks run on the configured schedule, or when someone runs them now." />
      ) : (
        <Table label="Recent checks">
          <caption className="sr-only">Recent checks</caption>
          <thead><tr><Th>Started</Th><Th>Trigger</Th><Th>Status</Th><Th align="right">Rules</Th><Th align="right">Findings</Th><Th align="right">New</Th><Th align="right">Cleared</Th></tr></thead>
          <tbody>
            {runs.data!.map((r) => (
              <tr key={r.id}>
                <Td className="whitespace-nowrap">{dateTime(r.startedAt)}</Td>
                <Td>{r.trigger.toLowerCase()}</Td>
                <Td>
                  <Badge tone={r.status === 'COMPLETED' ? 'success' : r.status === 'FAILED' ? 'danger' : 'info'}>{r.status}</Badge>
                  {r.failure && <span className="block text-[12px] text-danger">{r.failure}</span>}
                </Td>
                <Td align="right">{r.rulesEvaluated}</Td>
                <Td align="right">{r.findings}</Td>
                <Td align="right">{r.opened}</Td>
                <Td align="right">{r.cleared}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
