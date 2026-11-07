import { describe, expect, it } from 'vitest';
import type { Plan } from '@/api/types';
import { planActions, stepsWithOutcome } from './recovery';

const plan = (over: Partial<Plan>): Plan => ({
  id: 'p1', incidentId: 'i1', planNumber: 1, fingerprint: 'sha256:' + 'a'.repeat(64), risk: 'HIGH', requiresApproval: true,
  stepCount: 2, manualStepCount: 0, status: 'PROPOSED', impact: { counts: {}, traversedEdges: 0, records: [] },
  plan: { steps: [
    { number: 1, action: 'RESTORE_FIELDS', key: { entityType: 'product', entityId: 'x' }, expectedVersion: 1, changes: [],
      dependsOn: [], risk: 'LOW', reason: 'r', amount: null },
    { number: 2, action: 'UPDATE_FIELDS', key: { entityType: 'order', entityId: 'y' }, expectedVersion: 3, changes: [],
      dependsOn: [1], risk: 'LOW', reason: 'r', amount: null },
  ], invariants: [] },
  createdBy: 'author', createdAt: '2026-11-05T10:00:00Z', ...over,
});
const everyone = { canApprove: true, canExecute: true, canVerify: true, userId: 'someone' };

describe('planActions', () => {
  it('lets an approver who did not write the plan approve it, and nobody run it first', () => {
    const a = planActions(plan({}), everyone);
    expect(a.approve).toBe(true);
    expect(a.execute).toBeNull();
    expect(a.verify).toBe(false);
  });

  it('never offers the author approval, and says why', () => {
    const a = planActions(plan({}), { ...everyone, userId: 'author' });
    expect(a.approve).toBe(false);
    expect(a.approveBlocked).toMatch(/someone else/);
  });

  it('runs a low-risk plan that needs no approval, resumes a stopped one, verifies a finished one', () => {
    expect(planActions(plan({ requiresApproval: false }), everyone).execute).toBe('run');
    expect(planActions(plan({ status: 'APPROVED' }), everyone).execute).toBe('run');
    expect(planActions(plan({ status: 'EXECUTING' }), everyone).execute).toBe('resume');
    const done = planActions(plan({ status: 'FAILED' }), everyone);
    expect(done.execute).toBeNull();
    expect(done.verify).toBe(true);
    expect(done.acknowledge).toBe(true);
  });

  it('offers nothing without the permissions', () => {
    const a = planActions(plan({ status: 'APPROVED' }), { canApprove: false, canExecute: false, canVerify: false, userId: 'u' });
    expect(a).toEqual({ approve: false, approveBlocked: null, reject: false, execute: null, verify: false, acknowledge: false });
  });

  it('offers nothing on superseded or rejected plans', () => {
    for (const status of ['SUPERSEDED', 'REJECTED'] as const) {
      const a = planActions(plan({ status }), everyone);
      expect([a.approve, a.reject, a.execute, a.verify]).toEqual([false, false, null, false]);
    }
  });
});

describe('stepsWithOutcome', () => {
  it('joins outcomes to steps by number and leaves unrun steps empty', () => {
    const rows = stepsWithOutcome(plan({}), [{ stepNo: 2, status: 'BLOCKED', detail: 'Step 1 did not complete',
      resultVersion: null, resultEntityId: null, executedBy: 'u', recordedAt: 'x', acknowledgedBy: null,
      acknowledgedAt: null, acknowledgement: null }]);
    expect(rows.map((r) => r.outcome?.status ?? null)).toEqual([null, 'BLOCKED']);
  });
});

describe('impact classifications', () => {
  it('shows the two not-reached classes and keeps the legacy label only for older stored plans', async () => {
    const { classificationsFor, classificationLabel, classificationHelp } = await import('./recovery');
    const current = classificationsFor({ NOT_REACHED_PROVENANCE_COMPLETE: 2 });
    expect(current).toContain('NOT_REACHED_PROVENANCE_COMPLETE');
    expect(current).toContain('NOT_REACHED_PROVENANCE_INCOMPLETE');
    expect(current).toContain('NO_LONGER_AFFECTED');
    expect(current).not.toContain('UNAFFECTED');
    expect(classificationsFor({ UNAFFECTED: 2 })).toContain('UNAFFECTED');
    expect(classificationLabel.UNAFFECTED).toMatch(/older plan/);
    // Nothing says "verified unaffected"; "complete" is scoped and disclaims proof.
    expect(Object.values(classificationLabel).join(' ')).not.toMatch(/verified unaffected/i);
    expect(classificationHelp.NOT_REACHED_PROVENANCE_COMPLETE).toMatch(/within that scope; it is not proof/);
    expect(classificationHelp.NOT_REACHED_PROVENANCE_INCOMPLETE).toMatch(/cannot be ruled out/);
  });
});
