import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, FlaskConical, GitCompareArrows, XCircle } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { keys, recovery } from '@/api/endpoints';
import type {
  ConflictKind, FieldConflict, IncidentConflicts, OptimizerCandidate, RecordAction, RecordCategory, ReviewDecision,
  SimulationView, StrategyDetailTotals, StrategyName, StrategyResultRow,
} from '@/api/types';
import { Badge, Button, Card, EmptyState, ErrorState, Field, InlineError, Input, Modal, Select, Spinner, Table, Td, Th } from '@/components/ui';
import { entityLabel, fieldLabel, historyPath, shortId } from '@/lib/audit';
import {
  STRATEGY_ORDER, conflictLabel, conflictTone, constraintLabel, reviewDecisionLabel, riskTone, strategyLabel,
} from '@/lib/atlasx';
import { cn, dateTime } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

type Tab = 'conflicts' | 'simulation';

/** ATLAS X panels of an incident: field-level conflicts and counterfactual simulation. */
export function IncidentInsights({ incidentId, planId }: { incidentId: string; planId: string | null }) {
  const [tab, setTab] = useState<Tab>('conflicts');
  return (
    <Card className="mt-4">
      <div role="tablist" aria-label="Incident analysis" className="flex gap-1 border-b border-border px-2">
        {([['conflicts', 'Conflicts', GitCompareArrows], ['simulation', 'Simulation', FlaskConical]] as const).map(([t, label, Icon]) => (
          <button key={t} type="button" role="tab" id={`tab-${t}`} aria-selected={tab === t} aria-controls={`panel-${t}`}
            onClick={() => setTab(t)}
            className={cn('flex items-center gap-2 border-b-2 px-3 py-2.5 text-[13px] font-medium',
              tab === t ? 'border-accent text-text' : 'border-transparent text-muted hover:text-text')}>
            <Icon className="h-4 w-4" aria-hidden />{label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'conflicts' ? <ConflictsPanel incidentId={incidentId} /> : <SimulationPanel incidentId={incidentId} planId={planId} />}
      </div>
    </Card>
  );
}

/* ---------------------------------------------------------------- conflicts */

function ConflictsPanel({ incidentId }: { incidentId: string }) {
  const { current } = useSession();
  const orgId = current?.id ?? null;
  const conflicts = useQuery({
    queryKey: keys.scoped(orgId, 'incident-conflicts', incidentId),
    queryFn: () => recovery.conflicts(incidentId),
  });
  const [reviewing, setReviewing] = useState<FieldConflict | null>(null);
  if (conflicts.isLoading) return <Spinner />;
  if (conflicts.isError) return <ErrorState error={conflicts.error} onRetry={() => void conflicts.refetch()} />;
  const data = conflicts.data!;
  if (data.conflicts.length === 0) {
    return <EmptyState title="Nothing to compare" body="No field carries the faulty value any more." />;
  }
  return (
    <>
      <p className="flex items-center gap-2 px-4 py-3 text-[13px]">
        {data.open > 0 ? <AlertTriangle className="h-4 w-4 text-warning" aria-hidden /> : <CheckCircle2 className="h-4 w-4 text-success" aria-hidden />}
        {data.open > 0
          ? `${data.open} conflict${data.open === 1 ? ' needs' : 's need'} a person's decision before the plan can run.`
          : 'No open conflicts: every field the fault reached can be corrected, or has a decision.'}
      </p>
      <Table label="Conflicts">
        <caption className="sr-only">Conflicts</caption>
        <thead>
          <tr><Th>Record</Th><Th>Field</Th><Th>Evidence</Th><Th>Decision</Th></tr>
        </thead>
        <tbody>
          {data.conflicts.map(({ conflict: c, review, open }) => (
            <tr key={`${c.key.entityType}:${c.key.entityId}.${c.field}.${c.kind}`} className="border-t border-border align-top">
              <Td>
                <Link className="text-accent hover:underline" to={historyPath(c.key.entityType, c.key.entityId, c.laterVersion ?? c.taintedTo)}>
                  {entityLabel(c.key.entityType)} <span className="font-mono text-[12px]">{shortId(c.key.entityId)}</span>
                </Link>
                <span className="block text-[12px] text-muted">faulty in v{c.taintedFrom}{c.taintedTo !== c.taintedFrom ? `–v${c.taintedTo}` : ''}</span>
              </Td>
              <Td>{fieldLabel(c.field)}</Td>
              <Td>
                <Badge tone={conflictTone[c.kind]}>{conflictLabel[c.kind]}</Badge>
                <p className="mt-1 max-w-xl text-[12px] text-muted">{c.explanation}</p>
              </Td>
              <Td>
                {review ? (
                  <div className="text-[12px]">
                    <span className="font-medium">{reviewDecisionLabel[review.decision]}</span>
                    <span className="block text-muted">{review.reviewerName ?? 'Reviewer'} · {dateTime(review.reviewedAt)}</span>
                    <span className="block text-muted">“{review.note}”</span>
                  </div>
                ) : open ? (
                  <Badge tone="warning">Needs review</Badge>
                ) : (
                  <span className="text-[12px] text-muted">{c.recommendation === 'CORRECT_AUTOMATICALLY' ? 'Corrected by the plan'
                    : c.recommendation === 'PRESERVE_LATER_CHANGE' ? 'Later value kept' : '—'}</span>
                )}
                <ReviewButton conflict={c} hasReview={!!review} onReview={setReviewing} />
              </Td>
            </tr>
          ))}
        </tbody>
      </Table>
      <ReviewDialog incidentId={incidentId} conflict={reviewing} onClose={() => setReviewing(null)} data={data} />
    </>
  );
}

function ReviewButton({ conflict, hasReview, onReview }: {
  conflict: FieldConflict; hasReview: boolean; onReview: (c: FieldConflict) => void;
}) {
  const { can } = useSession();
  if (!can(Perm.recoveryCreate) || conflict.kind === 'CONCURRENT_MODIFICATION' || conflict.recommendation !== 'HUMAN_REVIEW') {
    return null;
  }
  return (
    <Button size="sm" className="mt-1.5" onClick={() => onReview(conflict)}>{hasReview ? 'Change decision' : 'Review'}</Button>
  );
}

const DECISIONS: ReviewDecision[] = ['KEEP_LATER_CHANGE', 'RECOVERY_MAY_OVERWRITE', 'NEEDS_INVESTIGATION'];

function ReviewDialog({ incidentId, conflict, onClose, data }: {
  incidentId: string; conflict: FieldConflict | null; onClose: () => void; data: IncidentConflicts;
}) {
  const { current } = useSession();
  const qc = useQueryClient();
  const [decision, setDecision] = useState<ReviewDecision>('KEEP_LATER_CHANGE');
  const [note, setNote] = useState('');
  const save = useMutation({
    mutationFn: () => recovery.reviewConflict(incidentId, {
      entityType: conflict!.key.entityType, entityId: conflict!.key.entityId, field: conflict!.field, decision, note,
    }),
    onSuccess: async () => {
      setNote('');
      await qc.invalidateQueries({ queryKey: keys.scoped(current?.id ?? null, 'incident-conflicts', incidentId) });
      onClose();
    },
  });
  const kind: ConflictKind | undefined = conflict?.kind;
  return (
    <Modal open={!!conflict} onClose={onClose} title="Review conflict"
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button variant="primary" loading={save.isPending} disabled={note.trim().length < 3} onClick={() => save.mutate()}>Record decision</Button>
      </>}>
      {conflict && kind && (
        <div className="space-y-3 text-[13px]">
          <p><Badge tone={conflictTone[kind]}>{conflictLabel[kind]}</Badge></p>
          <p className="text-muted">{conflict.explanation}</p>
          <Field label="Decision">
            {(p) => (
              <Select {...p} value={decision} onChange={(e) => setDecision(e.target.value as ReviewDecision)}>
                {DECISIONS.map((d) => <option key={d} value={d}>{reviewDecisionLabel[d]}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Why" hint="Recorded with your name; decisions cannot be edited, only superseded.">
            {(p) => <Input {...p} value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} />}
          </Field>
          <InlineError error={save.error} />
          <p className="text-[12px] text-muted">{data.open} open conflict(s) in this incident.</p>
        </div>
      )}
    </Modal>
  );
}

/* ---------------------------------------------------------------- simulation */

function SimulationPanel({ incidentId, planId }: { incidentId: string; planId: string | null }) {
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const qc = useQueryClient();
  const runsKey = keys.scoped(orgId, 'simulations', incidentId);
  const runs = useQuery({ queryKey: runsKey, queryFn: () => recovery.simulations(incidentId) });
  const latestId = runs.data?.[0]?.id ?? null;
  const view = useQuery({
    queryKey: keys.scoped(orgId, 'simulation', latestId),
    queryFn: () => recovery.simulation(latestId!),
    enabled: !!latestId,
  });
  const assessment = useQuery({
    queryKey: keys.scoped(orgId, 'assessment', planId, latestId),
    queryFn: () => recovery.assessment(planId!),
    enabled: !!planId,
  });
  const run = useMutation({
    mutationFn: () => recovery.simulate(incidentId),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: runsKey }); },
  });

  return (
    <div className="space-y-3 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-2xl text-[13px] text-muted">
          Runs six recovery strategies on an isolated in-memory copy of one consistent snapshot and compares what each
          would do. Nothing in the database changes; only the comparison is stored.
        </p>
        {can(Perm.recoveryCreate) && (
          <Button variant="primary" loading={run.isPending} onClick={() => run.mutate()}
            icon={<FlaskConical className="h-4 w-4" aria-hidden />}>
            {latestId ? 'Simulate again' : 'Run simulation'}
          </Button>
        )}
      </div>
      <InlineError error={run.error} />
      {assessment.data?.stale && latestId && (
        <p role="status" className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-[13px]">
          {assessment.data.staleReason}
        </p>
      )}
      {runs.isLoading || view.isLoading ? <Spinner /> : runs.isError ? <ErrorState error={runs.error} /> :
        !view.data ? <EmptyState title="No simulation yet" body="Run one to compare strategies before approving a plan." /> :
          <SimulationResult view={view.data} />}
    </div>
  );
}

function SimulationResult({ view }: { view: SimulationView }) {
  const { run, results } = view;
  const [details, setDetails] = useState<StrategyResultRow | null>(null);
  if (run.status === 'FAILED') {
    return <p role="alert" className="text-[13px] text-danger">Simulation failed: {run.failure}</p>;
  }
  const a = run.assessment;
  const candidate = (s: string): OptimizerCandidate | undefined => a?.candidates.find((c) => c.strategy === s);
  const ordered = STRATEGY_ORDER.map((s) => results.find((r) => r.strategy === s)).filter((r): r is StrategyResultRow => !!r);
  return (
    <>
      <div className="flex flex-wrap items-center gap-2 text-[13px]">
        {a && <Badge tone={riskTone[a.risk]}>Risk {a.risk}</Badge>}
        <span className="font-medium">{a?.summary}</span>
        <span className="text-muted">
          Snapshot at audit event #{run.snapshotChainIndex} · {run.records} records · {run.versions} versions · {run.durationMs} ms
          · {run.startedByName ?? 'someone'} {dateTime(run.finishedAt)}
        </span>
      </div>
      <Table label="Strategy comparison">
        <caption className="sr-only">Strategy comparison</caption>
        <thead>
          <tr>
            <Th>Strategy</Th><Th align="right">Repaired</Th><Th align="right">Collateral</Th><Th align="right">Later edits lost</Th>
            <Th align="right">Deleted</Th><Th align="right">Rule breaks</Th><Th align="right">Conflicts</Th><Th align="right">Writes</Th>
            <Th>Constraints</Th><Th align="right">Score</Th>
          </tr>
        </thead>
        <tbody>
          {ordered.map((r) => {
            const c = candidate(r.strategy);
            const best = a?.recommended === r.strategy;
            return (
              <tr key={r.strategy} className={cn('border-t border-border', best && 'bg-success/5')}>
                <Td>
                  <button type="button" className="text-left text-accent hover:underline" onClick={() => setDetails(r)}>
                    {r.letter}. {strategyLabel[r.strategy]}
                  </button>
                  {best && <span className="ml-2"><Badge tone="success">Recommended</Badge></span>}
                  {!r.applicable && <span className="block text-[12px] text-muted">Not applicable: {r.notApplicableReason}</span>}
                </Td>
                <Td align="right">{r.applicable ? `${r.repaired} / ${r.faultyRecords}` : '—'}</Td>
                <Td align="right">{r.applicable ? r.collateralModified : '—'}</Td>
                <Td align="right">{r.applicable ? r.legitimateOverwritten : '—'}</Td>
                <Td align="right">{r.applicable ? r.recordsDeleted : '—'}</Td>
                <Td align="right">{r.applicable ? r.invariantViolations : '—'}</Td>
                <Td align="right">{r.applicable ? (r.conflictsIgnored > 0 ? `${r.conflictsIgnored} ignored` : `${r.conflictsSurfaced} to review`) : '—'}</Td>
                <Td align="right">{r.applicable ? r.fieldWrites : '—'}</Td>
                <Td>
                  {c && (c.feasible
                    ? <span className="inline-flex items-center gap-1 text-[12px] text-success"><CheckCircle2 className="h-3.5 w-3.5" aria-hidden />all met</span>
                    : <span className="inline-flex items-center gap-1 text-[12px] text-danger"><XCircle className="h-3.5 w-3.5" aria-hidden />
                      {c.constraints.filter((k) => !k.passed).length} broken</span>)}
                </Td>
                <Td align="right">{r.applicable && c ? c.score.total : '—'}</Td>
              </tr>
            );
          })}
        </tbody>
      </Table>
      {a && (
        <section aria-label="Risk factors">
          <h4 className="mb-1.5 mt-2 text-[11px] font-semibold uppercase tracking-wide text-muted">Why this risk level</h4>
          <ul className="grid gap-2 md:grid-cols-2">
            {a.riskFactors.map((f) => (
              <li key={f.factor} className="rounded-md border border-border p-2.5 text-[13px]">
                <span className="flex items-center gap-2"><Badge tone={riskTone[f.level]}>{f.level}</Badge>
                  <span className="font-medium">{f.factor.toLowerCase().replace(/_/g, ' ')}</span>
                  <span className="text-muted">{f.value}</span></span>
                <span className="mt-1 block text-[12px] text-muted">{f.explanation}</span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-[12px] text-muted">
            Score J = {a.weights.collateral}·collateral + {a.weights.unresolved}·unresolved + {a.weights.risk}·risk + {a.weights.cost}·cost,
            only among candidates that meet every hard constraint.
          </p>
        </section>
      )}
      {ordered.filter((r) => r.applicable).length >= 2 && (
        <StrategyCompare runId={run.id} applicable={ordered.filter((r) => r.applicable).map((r) => r.strategy)} />
      )}
      <Modal open={!!details} onClose={() => setDetails(null)} side
        title={details ? `${details.letter}. ${strategyLabel[details.strategy]}` : ''}>
        {details && <StrategyDetails runId={run.id} r={details} c={candidate(details.strategy)} />}
      </Modal>
    </>
  );
}

function StrategyDetails({ runId, r, c }: { runId: string; r: StrategyResultRow; c: OptimizerCandidate | undefined }) {
  return (
    <div className="space-y-4 text-[13px]">
      {c && (
        <section>
          <h4 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted">Hard constraints</h4>
          <ul className="space-y-1.5">
            {c.constraints.map((k) => (
              <li key={k.code} className="flex gap-2">
                {k.passed ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success" aria-label="met" />
                  : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-danger" aria-label="broken" />}
                <span><span className="font-medium">{constraintLabel[k.code] ?? k.code}</span>
                  {k.detail && <span className="block text-[12px] text-muted">{k.detail}</span>}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
        <dt className="text-muted">Records inspected</dt><dd>{r.recordsInspected}</dd>
        <dt className="text-muted">Records modified</dt><dd>{r.recordsModified}</dd>
        <dt className="text-muted">Compensations</dt><dd>{r.compensations}</dd>
        <dt className="text-muted">Manual steps</dt><dd>{r.manualSteps}</dd>
        <dt className="text-muted">Simulation time</dt><dd>{(r.simulationMicros / 1000).toFixed(1)} ms</dd>
      </dl>
      {r.violations.length > 0 && (
        <section>
          <h4 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted">Rules it would break</h4>
          <ul className="list-disc space-y-1 pl-5 text-[12px]">{r.violations.map((v) => <li key={v}>{v}</li>)}</ul>
        </section>
      )}
      {r.notes.length > 0 && <ul className="space-y-1 text-[12px] text-muted">{r.notes.map((n) => <li key={n}>{n}</li>)}</ul>}
      {r.applicable && <StrategyRecords runId={runId} strategy={r.strategy} />}
    </div>
  );
}

const CATEGORIES: { key: RecordCategory; label: string; count: (d: StrategyDetailTotals) => number }[] = [
  { key: 'PROPOSED', label: 'Proposed', count: (d) => d.proposed },
  { key: 'MODIFIED', label: 'Modified', count: (d) => d.modified },
  { key: 'PRESERVED', label: 'Preserved', count: (d) => d.preserved },
  { key: 'CONFLICTING', label: 'Conflicting', count: (d) => d.conflicting },
  { key: 'UNRESOLVED', label: 'Unresolved', count: (d) => d.unresolved },
  { key: 'INSPECTED', label: 'Inspected', count: (d) => d.inspected },
];

const actionLabel: Record<RecordAction, string> = {
  DELETE: 'Delete', MODIFY: 'Modify', COMPENSATE: 'Compensate', REVIEW: 'Person decides', HELD: 'Held for review', NONE: '—',
};

/** A strategy's record-level results: exact counts, then the stored details, labelled partial when cut. */
function StrategyRecords({ runId, strategy }: { runId: string; strategy: string }) {
  const { current } = useSession();
  const [category, setCategory] = useState<RecordCategory | undefined>(undefined);
  const [page, setPage] = useState(0);
  const size = 50;
  const q = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'strategy-records', runId, strategy, category, page),
    queryFn: () => recovery.strategyRecords(runId, strategy, { category, page, size }),
  });
  if (q.isLoading) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} />;
  const d = q.data!;
  if (!d.totals) {
    return <p className="text-[12px] text-muted">This simulation ran before record-level results were stored.</p>;
  }
  const t = d.totals;
  return (
    <section aria-label="Records" className="space-y-2">
      <h4 className="text-[11px] font-semibold uppercase tracking-wide text-muted">Records</h4>
      <p className="text-[12px] text-muted">
        {t.total} records took part · {t.preservedLaterChanges} later changes kept · {t.unresolvedDependencies} step(s) waiting on a decision
      </p>
      {d.partial && (
        <p role="note" className="rounded-md border border-warning/30 bg-warning/10 px-2.5 py-1.5 text-[12px]">
          Partial: {t.stored} of {t.total} records are stored ({t.omitted} omitted, most important first). Counts are exact;
          the list below is not complete.
        </p>
      )}
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Show records">
        <Button size="sm" variant={!category ? 'secondary' : 'ghost'} aria-pressed={!category}
          onClick={() => { setCategory(undefined); setPage(0); }}>All {t.total}</Button>
        {CATEGORIES.map((k) => (
          <Button key={k.key} size="sm" variant={category === k.key ? 'secondary' : 'ghost'} aria-pressed={category === k.key}
            onClick={() => { setCategory(k.key); setPage(0); }}>{k.label} {k.count(t)}</Button>
        ))}
      </div>
      {d.items.length === 0 ? <p className="text-[12px] text-muted">No stored records in this category.</p> : (
        <ul className="divide-y divide-border rounded-md border border-border text-[12px]">
          {d.items.map((x) => (
            <li key={x.seq} className="px-2.5 py-1.5">
              <Link className="text-accent hover:underline" to={historyPath(x.entityType, x.entityId)}>
                {entityLabel(x.entityType)} <span className="font-mono">{shortId(x.entityId)}</span>
              </Link>
              <span className="ml-2 font-medium">{actionLabel[x.action]}</span>
              {x.fields.length > 0 && <span className="text-muted"> · {x.fields.map(fieldLabel).join(', ')}</span>}
              {x.conflictKind && <span className="ml-1"><Badge tone="danger">{conflictLabel[x.conflictKind as ConflictKind] ?? x.conflictKind}</Badge></span>}
              {x.preserved && <span className="ml-1"><Badge tone="success">Later change kept</Badge></span>}
              {x.unresolved && !x.conflictKind && <span className="ml-1"><Badge tone="warning">Unresolved</Badge></span>}
            </li>
          ))}
        </ul>
      )}
      <p className="text-[12px] text-muted">
        {d.storedMatching} stored {d.partial ? '(of more)' : ''} · page {d.page + 1}
        {d.page > 0 && <Button size="sm" variant="ghost" className="ml-2" onClick={() => setPage(d.page - 1)}>Previous</Button>}
        {(d.page + 1) * d.size < d.storedMatching && <Button size="sm" variant="ghost" className="ml-1" onClick={() => setPage(d.page + 1)}>Next</Button>}
      </p>
    </section>
  );
}

/** Two strategies side by side: exact counts from the complete record sets, stored differences (maybe partial). */
function StrategyCompare({ runId, applicable }: { runId: string; applicable: StrategyName[] }) {
  const { current } = useSession();
  const [a, setA] = useState<StrategyName>(applicable[0]!);
  const [b, setB] = useState<StrategyName>(applicable[applicable.length - 1]!);
  const q = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'strategy-comparison', runId, a, b),
    queryFn: () => recovery.comparison(runId, a, b),
    enabled: a !== b,
  });
  return (
    <section aria-label="Compare two strategies" className="mt-3 rounded-md border border-border p-3 text-[13px]">
      <h4 className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted">Compare two strategies</h4>
      <div className="flex flex-wrap gap-3">
        <Field label="Strategy A">{(p) => (
          <Select {...p} value={a} onChange={(e) => setA(e.target.value as StrategyName)}>
            {applicable.map((s) => <option key={s} value={s}>{strategyLabel[s]}</option>)}
          </Select>)}</Field>
        <Field label="Strategy B">{(p) => (
          <Select {...p} value={b} onChange={(e) => setB(e.target.value as StrategyName)}>
            {applicable.map((s) => <option key={s} value={s}>{strategyLabel[s]}</option>)}
          </Select>)}</Field>
      </div>
      {a === b ? <p className="mt-2 text-[12px] text-muted">Choose two different strategies.</p>
        : q.isLoading ? <Spinner /> : q.isError ? <ErrorState error={q.error} /> : q.data && (() => {
          const x = q.data;
          const flip = x.strategyA !== a;
          const onlyA = flip ? x.onlyB : x.onlyA;
          const onlyB = flip ? x.onlyA : x.onlyB;
          return (
            <div className="mt-2 space-y-2">
              <p role="status">
                Only A acts on {onlyA} · only B acts on {onlyB} · both act on {x.both} ({x.sameAction} the same way) ·
                {' '}{x.totalDifferences} difference{x.totalDifferences === 1 ? '' : 's'}
              </p>
              {x.truncated && (
                <p role="note" className="rounded-md border border-warning/30 bg-warning/10 px-2.5 py-1.5 text-[12px]">
                  Partial: {x.storedDifferences} of {x.totalDifferences} differences are stored ({x.omitted} omitted). The counts
                  above are exact.
                </p>
              )}
              {x.differences && x.differences.length > 0 && (
                <Table label="Differences">
                  <caption className="sr-only">Differences</caption>
                  <thead><tr><Th>Record</Th><Th>A</Th><Th>B</Th></tr></thead>
                  <tbody>
                    {x.differences.slice(0, 200).map((d) => {
                      const [l, r] = flip ? [[d.actionB, d.fieldsB], [d.actionA, d.fieldsA]] as const : [[d.actionA, d.fieldsA], [d.actionB, d.fieldsB]] as const;
                      return (
                        <tr key={`${d.entityType}:${d.entityId}`}>
                          <Td>{entityLabel(d.entityType)} <span className="font-mono text-[12px]">{shortId(d.entityId)}</span></Td>
                          <Td>{actionLabel[l[0]]}{l[1].length > 0 && <span className="block text-[12px] text-muted">{l[1].map(fieldLabel).join(', ')}</span>}</Td>
                          <Td>{actionLabel[r[0]]}{r[1].length > 0 && <span className="block text-[12px] text-muted">{r[1].map(fieldLabel).join(', ')}</span>}</Td>
                        </tr>
                      );
                    })}
                  </tbody>
                </Table>
              )}
              {x.differences && x.differences.length > 200 && (
                <p className="text-[12px] text-muted">Showing the first 200 of {x.storedDifferences} stored differences.</p>
              )}
              <p className="font-mono text-[11px] text-muted">sha256 {x.sha256.slice(0, 16)}…</p>
            </div>
          );
        })()}
    </section>
  );
}
