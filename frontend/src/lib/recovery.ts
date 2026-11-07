import type { ActionRow, Classification, Plan, PlanStatus, PlanStep, StepAction, StepStatus } from '@/api/types';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

export const classificationTone: Record<Classification, Tone> = {
  ORIGIN: 'danger',
  DIRECTLY_AFFECTED: 'warning',
  INDIRECTLY_AFFECTED: 'warning',
  NON_REVERSIBLE: 'danger',
  CONFLICTED: 'danger',
  REQUIRES_MANUAL_REVIEW: 'info',
  NO_LONGER_AFFECTED: 'neutral',
  NOT_REACHED_PROVENANCE_COMPLETE: 'neutral',
  NOT_REACHED_PROVENANCE_INCOMPLETE: 'warning',
  UNAFFECTED: 'neutral',
};

export const classificationLabel: Record<Classification, string> = {
  ORIGIN: 'Origin',
  DIRECTLY_AFFECTED: 'Directly affected',
  INDIRECTLY_AFFECTED: 'Indirectly affected',
  NON_REVERSIBLE: 'Final (compensate)',
  CONFLICTED: 'Conflicted',
  REQUIRES_MANUAL_REVIEW: 'Manual review',
  NO_LONGER_AFFECTED: 'No longer affected',
  NOT_REACHED_PROVENANCE_COMPLETE: 'Not reached, provenance complete',
  NOT_REACHED_PROVENANCE_INCOMPLETE: 'Not reached, provenance incomplete',
  UNAFFECTED: 'Context only (older plan)',
};

/** What a context class does and does not claim; shown next to the counts. */
export const classificationHelp: Partial<Record<Classification, string>> = {
  NO_LONGER_AFFECTED: 'Reached from the faulty change, but no field carries the faulty value any more.',
  NOT_REACHED_PROVENANCE_COMPLETE: 'No recorded path from the faulty change, and every required input of the record is recorded '
    + 'within the analysed scope. This rules out impact only within that scope; it is not proof of no impact.',
  NOT_REACHED_PROVENANCE_INCOMPLETE: 'No recorded path from the faulty change, but a required input of the record was never '
    + 'recorded, so impact cannot be ruled out.',
  UNAFFECTED: 'A plan stored before these records were told apart: reached-but-clean and not-reached records share this label.',
};

/** Display order for impact counts: what will be changed first, context last. */
export const CLASSIFICATIONS: Classification[] = [
  'ORIGIN', 'DIRECTLY_AFFECTED', 'INDIRECTLY_AFFECTED', 'NON_REVERSIBLE', 'CONFLICTED', 'REQUIRES_MANUAL_REVIEW',
  'NO_LONGER_AFFECTED', 'NOT_REACHED_PROVENANCE_COMPLETE', 'NOT_REACHED_PROVENANCE_INCOMPLETE',
];

/** The classes to show for one report: the legacy label only where an older stored plan used it. */
export function classificationsFor(counts: Partial<Record<string, number>>): Classification[] {
  return (counts.UNAFFECTED ?? 0) > 0 ? [...CLASSIFICATIONS, 'UNAFFECTED'] : CLASSIFICATIONS;
}

export const actionLabel: Record<StepAction, string> = {
  RESTORE_FIELDS: 'Restore',
  UPDATE_FIELDS: 'Recompute',
  ISSUE_CREDIT_NOTE: 'Credit note',
  SUPPLEMENTARY_CHARGE: 'Charge owed (manual)',
  MANUAL_REVIEW: 'Manual review',
  NO_ACTION: 'No action',
};

export const stepTone: Record<StepStatus, Tone> = {
  DONE: 'success',
  SKIPPED: 'neutral',
  AWAITING_ACKNOWLEDGEMENT: 'warning',
  ACKNOWLEDGED: 'success',
  FAILED: 'danger',
  BLOCKED: 'danger',
};

export const planTone: Record<PlanStatus, Tone> = {
  PROPOSED: 'info',
  APPROVED: 'info',
  EXECUTING: 'warning',
  EXECUTED: 'success',
  FAILED: 'danger',
  REJECTED: 'neutral',
  SUPERSEDED: 'neutral',
};

export function isManual(step: PlanStep): boolean {
  return step.action === 'MANUAL_REVIEW' || step.action === 'SUPPLEMENTARY_CHARGE';
}

export interface PlanPermissions {
  canApprove: boolean;
  canExecute: boolean;
  canVerify: boolean;
  userId: string | null;
}

export interface PlanActions {
  approve: boolean;
  /** Why approval is not offered to someone who otherwise could approve. */
  approveBlocked: string | null;
  reject: boolean;
  execute: 'run' | 'resume' | null;
  verify: boolean;
  acknowledge: boolean;
}

/**
 * What the current user may do with a plan. A convenience for the screen
 * only: the server enforces every one of these rules (permissions, the
 * author-cannot-approve rule, approval before execution, statuses).
 */
export function planActions(plan: Plan, p: PlanPermissions): PlanActions {
  const author = p.userId !== null && p.userId === plan.createdBy;
  const proposed = plan.status === 'PROPOSED';
  let execute: PlanActions['execute'] = null;
  if (p.canExecute) {
    if (plan.status === 'APPROVED' || (proposed && !plan.requiresApproval)) execute = 'run';
    if (plan.status === 'EXECUTING') execute = 'resume';
  }
  const finished = plan.status === 'EXECUTED' || plan.status === 'FAILED';
  return {
    approve: proposed && p.canApprove && !author,
    approveBlocked: proposed && p.canApprove && author ? 'You generated this plan, so someone else must approve it.' : null,
    reject: (proposed || plan.status === 'APPROVED') && p.canApprove,
    execute,
    verify: finished && p.canVerify,
    acknowledge: finished && p.canExecute,
  };
}

/** The plan's steps joined with what happened to each, in step order. */
export function stepsWithOutcome(plan: Plan, actions: ActionRow[] | undefined): { step: PlanStep; outcome: ActionRow | null }[] {
  const byNo = new Map((actions ?? []).map((a) => [a.stepNo, a]));
  return plan.plan.steps.map((step) => ({ step, outcome: byNo.get(step.number) ?? null }));
}

export function shortFingerprint(fp: string): string {
  return fp.replace(/^sha256:/, '').slice(0, 12);
}
