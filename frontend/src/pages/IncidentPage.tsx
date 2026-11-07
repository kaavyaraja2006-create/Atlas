import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, Play, RotateCcw, ShieldCheck, Sparkles, XCircle } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { keys, recovery } from '@/api/endpoints';
import type { ActionRow, AffectedRecord, EntityKey, Plan, PlanStep, VerificationRow } from '@/api/types';
import {
  Badge, Button, Card, ConfirmAction, EmptyState, ErrorState, Field, InlineError, Input, Modal, Pagination, Spinner,
  Table, Td, Th,
} from '@/components/ui';
import { entityLabel, fieldLabel, formatValue, historyPath, shortId } from '@/lib/audit';
import { dateTime, money } from '@/lib/format';
import {
  actionLabel, classificationHelp, classificationLabel, classificationTone, classificationsFor, isManual, planActions, planTone,
  shortFingerprint, stepTone, stepsWithOutcome,
} from '@/lib/recovery';
import { Perm, useSession } from '@/session/SessionProvider';
import { IncidentStatusBadge } from './RecoveryPage';
import { IncidentInsights } from '@/components/IncidentInsights';

const PAGE = 25;

function RecordLink({ k, version }: { k: EntityKey; version?: number }) {
  return (
    <Link className="text-accent hover:underline" to={historyPath(k.entityType, k.entityId, version)}>
      {entityLabel(k.entityType)} <span className="font-mono text-[12px]">{shortId(k.entityId)}</span>
    </Link>
  );
}

export function IncidentPage() {
  const { id = '' } = useParams();
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const queryClient = useQueryClient();
  const incidentKey = keys.scoped(orgId, 'incident', id);
  const plansKey = keys.scoped(orgId, 'incident-plans', id);
  const incident = useQuery({ queryKey: incidentKey, queryFn: () => recovery.incident(id) });
  const plans = useQuery({ queryKey: plansKey, queryFn: () => recovery.plans(id), enabled: incident.isSuccess });
  const [analysing, setAnalysing] = useState(false);
  const impact = useQuery({
    queryKey: keys.scoped(orgId, 'incident-impact', id),
    queryFn: () => recovery.impact(id),
    enabled: analysing,
  });
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: incidentKey });
    await queryClient.invalidateQueries({ queryKey: plansKey });
    await queryClient.invalidateQueries({ queryKey: keys.scoped(orgId, 'incidents') });
  };
  const generate = useMutation({ mutationFn: () => recovery.generate(id), onSuccess: refresh });

  if (incident.isLoading) return <Spinner />;
  if (incident.isError || !incident.data) return <ErrorState error={incident.error} onRetry={() => void incident.refetch()} />;
  const inc = incident.data;
  const latest = plans.data?.[0];
  const canPlan = can(Perm.recoveryCreate) && ['OPEN', 'PLANNED', 'APPROVED'].includes(inc.status)
    && latest?.status !== 'EXECUTING';

  return (
    <>
      <Link to="/recovery" className="mb-3 inline-flex items-center gap-1 text-[13px] text-muted hover:text-text">
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> Recovery
      </Link>
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-mono text-xl font-semibold">{inc.incidentNumber}</h1>
            <IncidentStatusBadge status={inc.status} />
          </div>
          <p className="mt-1 font-medium">{inc.title}</p>
          <p className="mt-0.5 text-muted">{inc.reason} · opened {dateTime(inc.openedAt)}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {!latest && (
            <Button icon={<Sparkles className="h-4 w-4" aria-hidden />} loading={impact.isFetching}
              onClick={() => (analysing ? void impact.refetch() : setAnalysing(true))}>
              Analyse impact
            </Button>
          )}
          {canPlan && (
            <Button variant={latest ? 'secondary' : 'primary'} icon={<RotateCcw className="h-4 w-4" aria-hidden />}
              loading={generate.isPending} onClick={() => generate.mutate()}>
              {latest ? 'Regenerate plan' : 'Generate plan'}
            </Button>
          )}
        </div>
      </div>
      <div className="mb-4"><InlineError error={generate.error ?? impact.error} /></div>

      {plans.isLoading ? <Spinner /> : latest ? (
        <PlanPanel plan={latest} older={plans.data!.slice(1)} onChanged={refresh} />
      ) : impact.data ? (
        <ImpactCard counts={impact.data.counts} records={impact.data.report.records} live />
      ) : (
        <Card><EmptyState title="No plan yet"
          body="Analyse the impact to see which records consumed the faulty value, or generate a plan directly. Neither changes any data." /></Card>
      )}
      <IncidentInsights incidentId={id} planId={latest?.id ?? null} />
    </>
  );
}

/* ---------------------------------------------------------------- impact */

function ImpactCard({ counts, records, live = false }: {
  counts: Partial<Record<string, number>>; records: AffectedRecord[]; live?: boolean;
}) {
  const [page, setPage] = useState(0);
  const shown = records.slice(page * PAGE, (page + 1) * PAGE);
  return (
    <Card className="mb-4">
      <div className="border-b border-border px-4 py-3">
        <h2 className="font-semibold">Impact</h2>
        <p className="text-[13px] text-muted">
          {live ? 'Analysis of the current data. Nothing has been changed.' : 'As analysed when this plan was generated.'}
        </p>
      </div>
      <dl className="grid grid-cols-2 gap-px bg-border sm:grid-cols-3 lg:grid-cols-5">
        {classificationsFor(counts).map((c) => (
          <div key={c} className="bg-surface px-4 py-3">
            <dt className="text-[12px] text-muted">{classificationLabel[c]}</dt>
            <dd className="num text-lg font-semibold">{counts[c] ?? 0}</dd>
          </div>
        ))}
      </dl>
      <ul aria-label="What the context classes mean" className="space-y-1 border-t border-border px-4 py-2.5 text-[12px] text-muted">
        {classificationsFor(counts).filter((c) => classificationHelp[c] && (counts[c] ?? 0) > 0).map((c) => (
          <li key={c}><span className="font-medium text-text">{classificationLabel[c]}:</span> {classificationHelp[c]}</li>
        ))}
      </ul>
      <Table>
        <thead><tr><Th>Record</Th><Th>Classification</Th><Th align="right">Depth</Th><Th>Why</Th></tr></thead>
        <tbody>
          {shown.map((r) => (
            <tr key={`${r.key.entityType}:${r.key.entityId}`}>
              <Td><RecordLink k={r.key} version={r.currentVersion} /></Td>
              <Td><Badge tone={classificationTone[r.classification]}>{classificationLabel[r.classification]}</Badge></Td>
              <Td align="right">{r.depth}</Td>
              <Td className="text-muted">{r.reason ?? (r.taintedFields.length ? r.taintedFields.map(fieldLabel).join(', ') : '—')}</Td>
            </tr>
          ))}
        </tbody>
      </Table>
      <Pagination page={page} totalPages={Math.ceil(records.length / PAGE)} totalItems={records.length} onPage={setPage} />
    </Card>
  );
}

/* ---------------------------------------------------------------- plan */

function PlanPanel({ plan, older, onChanged }: { plan: Plan; older: Plan[]; onChanged: () => Promise<void> }) {
  const { current, can, me } = useSession();
  const orgId = current?.id ?? null;
  const queryClient = useQueryClient();
  const execKey = keys.scoped(orgId, 'plan-execution', plan.id);
  const verKey = keys.scoped(orgId, 'plan-verifications', plan.id);
  const execution = useQuery({ queryKey: execKey, queryFn: () => recovery.execution(plan.id) });
  const verifications = useQuery({ queryKey: verKey, queryFn: () => recovery.verifications(plan.id) });
  const actions = planActions(plan, {
    canApprove: can(Perm.recoveryApprove), canExecute: can(Perm.recoveryExecute), canVerify: can(Perm.auditRead),
    userId: me?.id ?? null,
  });
  const after = async () => {
    await queryClient.invalidateQueries({ queryKey: execKey });
    await queryClient.invalidateQueries({ queryKey: verKey });
    await onChanged();
  };
  const [dialog, setDialog] = useState<'approve' | 'reject' | 'execute' | null>(null);
  const [comment, setComment] = useState('');
  const close = () => { setDialog(null); setComment(''); approve.reset(); reject.reset(); execute.reset(); };
  const approve = useMutation({ mutationFn: () => recovery.approve(plan.id, plan.fingerprint, comment.trim() || undefined),
    onSuccess: async () => { close(); await after(); } });
  const reject = useMutation({ mutationFn: () => recovery.reject(plan.id, comment.trim()),
    onSuccess: async () => { close(); await after(); } });
  const execute = useMutation({ mutationFn: () => recovery.execute(plan.id), onSuccess: async () => { close(); await after(); } });
  const verify = useMutation({ mutationFn: () => recovery.verify(plan.id), onSuccess: after });

  const rows = useMemo(() => stepsWithOutcome(plan, execution.data?.steps), [plan, execution.data]);
  const credits = plan.plan.steps.filter((s) => s.action === 'ISSUE_CREDIT_NOTE').reduce((t, s) => t + (s.amount ?? 0), 0);
  const automatic = plan.plan.steps.filter((s) => !isManual(s) && s.action !== 'NO_ACTION').length;
  const approval = execution.data?.decisions.find((d) => d.decision === 'APPROVED');
  const rejection = execution.data?.decisions.find((d) => d.decision === 'REJECTED');

  return (
    <>
      <Card className="mb-4">
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-4 py-3">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="font-semibold">Plan {plan.planNumber}</h2>
              <Badge tone={planTone[plan.status]}>{plan.status}</Badge>
              <Badge tone={plan.risk === 'HIGH' ? 'danger' : plan.risk === 'MEDIUM' ? 'warning' : 'neutral'}>{plan.risk} risk</Badge>
            </div>
            <p className="mt-1 text-[13px] text-muted">
              {plan.stepCount} steps ({automatic} automatic, {plan.manualStepCount} for a person)
              {credits > 0 && <> · credit notes {money(credits, current?.currency)}</>}
              {' '}· fingerprint <span className="font-mono">{shortFingerprint(plan.fingerprint)}</span>
              {' '}· generated {dateTime(plan.createdAt)}
            </p>
            {approval && <p className="mt-1 text-[13px]">Approved by {approval.decidedByName ?? 'an approver'} {dateTime(approval.decidedAt)}{approval.comment ? ` — “${approval.comment}”` : ''}</p>}
            {rejection && <p className="mt-1 text-[13px]">Rejected by {rejection.decidedByName ?? 'an approver'}: “{rejection.comment}”</p>}
            {actions.approveBlocked && <p className="mt-1 text-[13px] text-muted">{actions.approveBlocked}</p>}
            {plan.status === 'PROPOSED' && plan.requiresApproval && !actions.approve && !actions.approveBlocked && (
              <p className="mt-1 text-[13px] text-muted">Waiting for a recovery approver.</p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            {actions.reject && <Button icon={<XCircle className="h-4 w-4" aria-hidden />} onClick={() => setDialog('reject')}>Reject</Button>}
            {actions.approve && <Button variant="primary" icon={<CheckCircle2 className="h-4 w-4" aria-hidden />} onClick={() => setDialog('approve')}>Approve</Button>}
            {actions.execute && (
              <Button variant="primary" icon={<Play className="h-4 w-4" aria-hidden />} onClick={() => setDialog('execute')}>
                {actions.execute === 'resume' ? 'Resume execution' : 'Execute plan'}
              </Button>
            )}
            {actions.verify && (
              <Button variant="primary" icon={<ShieldCheck className="h-4 w-4" aria-hidden />} loading={verify.isPending}
                onClick={() => verify.mutate()}>Verify</Button>
            )}
          </div>
        </div>
        <div className="px-4 pt-3"><InlineError error={verify.error} /></div>
        <StepsTable rows={rows} planId={plan.id} canAcknowledge={actions.acknowledge} onChanged={after} />
      </Card>

      {verifications.data && verifications.data.length > 0 && <VerificationCard v={verifications.data[0]!} total={verifications.data.length} />}
      <ImpactCard counts={plan.impact.counts} records={plan.impact.records} />
      {older.length > 0 && (
        <Card className="mb-4">
          <div className="border-b border-border px-4 py-3"><h2 className="font-semibold">Earlier plans</h2></div>
          <Table>
            <thead><tr><Th>Plan</Th><Th>Status</Th><Th align="right">Steps</Th><Th>Generated</Th></tr></thead>
            <tbody>
              {older.map((p) => (
                <tr key={p.id}>
                  <Td>Plan {p.planNumber}</Td>
                  <Td><Badge tone={planTone[p.status]}>{p.status}</Badge></Td>
                  <Td align="right">{p.stepCount}</Td>
                  <Td className="whitespace-nowrap text-muted">{dateTime(p.createdAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      <Modal open={dialog === 'approve'} onClose={close} title={`Approve plan ${plan.planNumber}?`}
        footer={<><Button onClick={close}>Cancel</Button>
          <Button variant="primary" loading={approve.isPending} onClick={() => approve.mutate()}>Approve</Button></>}>
        <div className="space-y-4">
          <p className="text-muted">
            You are approving exactly this plan (fingerprint <span className="font-mono">{shortFingerprint(plan.fingerprint)}</span>):
            {' '}{automatic} automatic changes{credits > 0 ? `, including credit notes of ${money(credits, current?.currency)}` : ''}.
            If any record it read changes before it runs, the approval no longer applies.
          </p>
          <Field label="Comment (optional)">{(p) => <Input {...p} value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>
          <InlineError error={approve.error} />
        </div>
      </Modal>
      <Modal open={dialog === 'reject'} onClose={close} title={`Reject plan ${plan.planNumber}?`}
        footer={<><Button onClick={close}>Cancel</Button>
          <Button variant="danger" disabled={!comment.trim()} loading={reject.isPending} onClick={() => reject.mutate()}>Reject</Button></>}>
        <div className="space-y-4">
          <Field label="Reason">{(p) => <Input {...p} value={comment} onChange={(e) => setComment(e.target.value)} />}</Field>
          <InlineError error={reject.error} />
        </div>
      </Modal>
      <ConfirmAction open={dialog === 'execute'} onClose={close}
        title={actions.execute === 'resume' ? 'Resume execution?' : `Execute plan ${plan.planNumber}?`}
        body={`This writes ${automatic} changes to live records, one step at a time. A step whose record changed since planning is skipped, not overwritten, and steps that depend on it are blocked. Every write is recorded in the audit log as a recovery change.`}
        confirmWord={`PLAN-${plan.planNumber}`} confirmLabel={actions.execute === 'resume' ? 'Resume' : 'Execute'}
        loading={execute.isPending} error={execute.error} onConfirm={() => execute.mutate()} />
    </>
  );
}

function ChangeCell({ step }: { step: PlanStep }) {
  const { current } = useSession();
  if (step.action === 'ISSUE_CREDIT_NOTE' || step.action === 'SUPPLEMENTARY_CHARGE') {
    return <span>{money(step.amount ?? 0, current?.currency)}</span>;
  }
  if (step.changes.length === 0) return <span className="text-muted">—</span>;
  return (
    <ul className="space-y-0.5">
      {step.changes.map((c) => (
        <li key={c.field} className="whitespace-nowrap">
          <span className="text-muted">{fieldLabel(c.field)}: </span>
          <span className="num text-muted line-through">{formatValue(c.current)}</span>
          {' → '}<span className="num font-medium">{formatValue(c.proposed)}</span>
        </li>
      ))}
    </ul>
  );
}

function StepsTable({ rows, planId, canAcknowledge, onChanged }: {
  rows: { step: PlanStep; outcome: ActionRow | null }[]; planId: string; canAcknowledge: boolean; onChanged: () => Promise<void>;
}) {
  const [page, setPage] = useState(0);
  const [acking, setAcking] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const ack = useMutation({
    mutationFn: () => recovery.acknowledge(planId, acking!, note.trim()),
    onSuccess: async () => { setAcking(null); setNote(''); await onChanged(); },
  });
  const shown = rows.slice(page * PAGE, (page + 1) * PAGE);
  return (
    <>
      <Table>
        <thead><tr><Th align="right">#</Th><Th>Step</Th><Th>Record</Th><Th>Change</Th><Th>Needs step</Th><Th>Outcome</Th></tr></thead>
        <tbody>
          {shown.map(({ step, outcome }) => (
            <tr key={step.number}>
              <Td align="right" className="text-muted">{step.number}</Td>
              <Td>
                <div className="whitespace-nowrap font-medium">{actionLabel[step.action]}</div>
                <div className="text-[12px] text-muted">{step.reason}</div>
              </Td>
              <Td className="whitespace-nowrap"><RecordLink k={step.key} version={step.expectedVersion} /> <span className="text-muted">v{step.expectedVersion}</span></Td>
              <Td><ChangeCell step={step} /></Td>
              <Td className="text-muted">{step.dependsOn.length ? step.dependsOn.join(', ') : '—'}</Td>
              <Td>
                {outcome ? (
                  <div>
                    <Badge tone={stepTone[outcome.status]}>{outcome.status.replace(/_/g, ' ')}</Badge>
                    <div className="mt-0.5 text-[12px] text-muted">{outcome.status === 'ACKNOWLEDGED' ? outcome.acknowledgement : outcome.detail}</div>
                    {canAcknowledge && outcome.status === 'AWAITING_ACKNOWLEDGEMENT' && (
                      <Button size="sm" className="mt-1" onClick={() => setAcking(step.number)}>Record what was done</Button>
                    )}
                  </div>
                ) : <span className="text-muted">Not run</span>}
              </Td>
            </tr>
          ))}
        </tbody>
      </Table>
      <Pagination page={page} totalPages={Math.ceil(rows.length / PAGE)} totalItems={rows.length} onPage={setPage} />
      <Modal open={acking !== null} onClose={() => { setAcking(null); ack.reset(); }} title={`Step ${acking ?? ''}: what was done?`}
        footer={<><Button onClick={() => setAcking(null)}>Cancel</Button>
          <Button variant="primary" disabled={!note.trim()} loading={ack.isPending} onClick={() => ack.mutate()}>Save</Button></>}>
        <div className="space-y-4">
          <p className="text-muted">Recovery does not change this record. Describe what a person decided or did about it; this is kept with the plan.</p>
          <Field label="What was done">{(p) => <Input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
          <InlineError error={ack.error} />
        </div>
      </Modal>
    </>
  );
}

function VerificationCard({ v, total }: { v: VerificationRow; total: number }) {
  const tone = v.outcome === 'PASSED' ? 'success' : v.outcome === 'INCOMPLETE' ? 'warning' : 'danger';
  const failing = v.checks.filter((c) => !c.passed);
  return (
    <Card className="mb-4">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-3">
        <h2 className="font-semibold">Verification</h2>
        <Badge tone={tone}>{v.outcome}</Badge>
        <span className="text-[13px] text-muted">
          {v.checkCount - v.failedCount} of {v.checkCount} checks passed
          {v.pendingManual > 0 && ` · ${v.pendingManual} manual step(s) awaiting a person`} · {dateTime(v.verifiedAt)}
          {total > 1 && ` · ${total} runs kept`}
        </span>
      </div>
      {failing.length === 0 ? (
        <p className="px-4 py-3 text-[13px] text-muted">
          Every changed field holds its planned value, totals equal their lines, credits are in place, only the plan's records
          were written, and a fresh analysis finds nothing left to repair automatically.
        </p>
      ) : (
        <Table>
          <thead><tr><Th>Failed check</Th><Th>Expected</Th><Th>Found</Th></tr></thead>
          <tbody>
            {failing.map((c, i) => (
              <tr key={i}>
                <Td className="whitespace-nowrap font-mono text-[12px]">{c.kind}</Td>
                <Td>{c.description}</Td>
                <Td className="text-danger">{c.actual}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}
