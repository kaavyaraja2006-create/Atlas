// Mirrors the backend's JSON contracts (Java records). Money arrives as JSON numbers.

export interface Page<T> {
  items: T[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
}

export interface AccessToken {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
}

export interface Me {
  id: string;
  email: string;
  displayName: string;
  platformAdmin: boolean;
}

export interface Organization {
  id: string;
  slug: string;
  name: string;
  currency: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt: string;
  version: number;
  myRoles: string[];
}

export interface Access {
  organizationId: string;
  roles: string[];
  permissions: string[];
}

export interface Member {
  userId: string;
  email: string;
  displayName: string;
  status: string;
  joinedAt: string;
  roles: string[];
  version: number;
}

export interface Product {
  id: string;
  organizationId: string;
  sku: string;
  name: string;
  unitPrice: number;
  unitCost: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface Customer {
  id: string;
  organizationId: string;
  name: string;
  email: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type OrderStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED';

export interface OrderLine {
  id: string;
  orderId: string;
  lineNo: number;
  productId: string;
  description: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  version: number;
}

export interface Order {
  id: string;
  organizationId: string;
  orderNumber: string;
  customerId: string;
  status: OrderStatus;
  totalAmount: number;
  createdBy: string;
  placedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  /** ATLAS X: editable after confirmation (a legitimate later edit to a different field). */
  deliveryNote?: string | null;
  lines: OrderLine[];
}

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'VOID';

export interface InvoiceLine {
  id: string;
  invoiceId: string;
  lineNo: number;
  orderLineId: string;
  description: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

export interface Invoice {
  id: string;
  organizationId: string;
  invoiceNumber: string;
  orderId: string;
  status: InvoiceStatus;
  totalAmount: number;
  issuedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  lines: InvoiceLine[];
}

export interface CreditNote {
  id: string;
  invoiceId: string;
  creditNoteNumber: string;
  amount: number;
  reason: string;
  status: 'DRAFT' | 'ISSUED';
  issuedAt: string | null;
  createdAt: string;
  version: number;
}

/** RFC 9457 problem details as produced by GlobalExceptionHandler. */
export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  errors?: string[];
  correlationId?: string;
}

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type Snapshot = Record<string, JsonValue>;

export interface AuditEvent {
  id: string;
  chainIndex: number;
  txnId: string;
  actorKind: 'USER' | 'SYSTEM' | 'RECOVERY';
  actorUserId: string | null;
  actorName: string | null;
  entityType: string;
  entityId: string;
  entityVersion: number;
  operation: 'INSERT' | 'UPDATE' | 'DELETE' | 'COMPENSATE';
  changedFields: string[];
  before: Snapshot | null;
  after: Snapshot | null;
  reason: string | null;
  correlationId: string | null;
  occurredAt: string;
  hash: string;
}

export interface ChainVerification {
  intact: boolean;
  firstBrokenIndex: number | null;
  eventCount: number;
}

export interface VersionRef {
  entityType: string;
  entityId: string;
  version: number;
}

export interface ProvenanceEdge {
  source: VersionRef;
  sourceField: string | null;
  target: VersionRef;
  targetField: string | null;
  relation: 'DERIVED_VALUE' | 'AGGREGATED_INTO' | 'COPIED_INTO';
  evidence: 'RECORDED_AT_WRITE' | 'BACKFILLED';
  /** Business operation that recorded the edge (ATLAS X); null for edges recorded before it existed. */
  operation?: string | null;
  txnId: string;
  recordedAt: string;
}

/* ---------------------------------------------------------------- Recovery (PASCAR) */

export type IncidentStatus = 'OPEN' | 'PLANNED' | 'APPROVED' | 'EXECUTING' | 'RESOLVED' | 'CANCELLED';
export type PlanStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'EXECUTING' | 'EXECUTED' | 'FAILED';
export type Classification =
  | 'ORIGIN' | 'DIRECTLY_AFFECTED' | 'INDIRECTLY_AFFECTED' | 'CONFLICTED'
  | 'NO_LONGER_AFFECTED' | 'NOT_REACHED_PROVENANCE_COMPLETE' | 'NOT_REACHED_PROVENANCE_INCOMPLETE'
  /** Only in plans stored before the not-reached classes existed. */
  | 'UNAFFECTED'
  | 'REQUIRES_MANUAL_REVIEW' | 'NON_REVERSIBLE';
export type StepAction =
  | 'RESTORE_FIELDS' | 'UPDATE_FIELDS' | 'ISSUE_CREDIT_NOTE' | 'SUPPLEMENTARY_CHARGE' | 'MANUAL_REVIEW' | 'NO_ACTION';
export type StepStatus = 'DONE' | 'FAILED' | 'BLOCKED' | 'SKIPPED' | 'AWAITING_ACKNOWLEDGEMENT' | 'ACKNOWLEDGED';
export type Risk = 'LOW' | 'MEDIUM' | 'HIGH';

export interface EntityKey {
  entityType: string;
  entityId: string;
}

export interface Incident {
  id: string;
  incidentNumber: string;
  faultyChangeEventId: string;
  title: string;
  reason: string;
  status: IncidentStatus;
  openedBy: string;
  openedAt: string;
  version: number;
}

export interface AffectedRecord {
  key: EntityKey;
  classification: Classification;
  depth: number;
  currentVersion: number;
  taintedFields: string[];
  path: string[];
  reason: string | null;
}

export interface ImpactView {
  incidentId: string;
  counts: Partial<Record<Classification, number>>;
  report: { records: AffectedRecord[]; traversedEdges: number; fingerprint: string };
}

export interface FieldChange {
  field: string;
  current: JsonValue;
  proposed: JsonValue;
}

export interface PlanStep {
  number: number;
  action: StepAction;
  key: EntityKey;
  expectedVersion: number;
  changes: FieldChange[];
  dependsOn: number[];
  risk: Risk;
  reason: string;
  amount: number | null;
}

export interface Invariant {
  kind: string;
  key: EntityKey | null;
  field: string | null;
  description: string;
}

export interface Plan {
  id: string;
  incidentId: string;
  planNumber: number;
  fingerprint: string;
  risk: Risk;
  requiresApproval: boolean;
  stepCount: number;
  manualStepCount: number;
  status: PlanStatus;
  impact: { counts: Partial<Record<Classification, number>>; traversedEdges: number; records: AffectedRecord[] };
  plan: { steps: PlanStep[]; invariants: Invariant[] };
  createdBy: string;
  createdAt: string;
}

export interface Decision {
  planId: string;
  userId: string;
  approved: boolean;
  fingerprint: string;
  comment: string | null;
}

export interface ActionRow {
  stepNo: number;
  status: StepStatus;
  detail: string;
  resultVersion: number | null;
  resultEntityId: string | null;
  executedBy: string;
  recordedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  acknowledgement: string | null;
}

export interface DecisionRow {
  decision: 'APPROVED' | 'REJECTED';
  fingerprint: string;
  comment: string | null;
  decidedBy: string;
  decidedByName: string | null;
  decidedAt: string;
}

export interface ExecutionView {
  planId: string;
  status: PlanStatus;
  steps: ActionRow[];
  decisions: DecisionRow[];
}

export interface ExecutionReport {
  status: PlanStatus;
  steps: { stepNo: number; status: StepStatus; detail: string }[];
}

export interface VerificationCheck {
  kind: string;
  key: EntityKey | null;
  description: string;
  passed: boolean;
  actual: string;
}

export interface VerificationRow {
  id: string;
  outcome: 'PASSED' | 'INCOMPLETE' | 'FAILED';
  checkCount: number;
  failedCount: number;
  pendingManual: number;
  checks: VerificationCheck[];
  verifiedBy: string;
  verifiedAt: string;
}

/* ---------------------------------------------------------------- ATLAS X: provenance and history */

export type Confidence = 'VERIFIED' | 'DECLARED' | 'INFERRED' | 'UNKNOWN';

export interface LineageEdge {
  depth: number;
  source: VersionRef;
  sourceField: string;
  target: VersionRef;
  targetField: string;
  relation: ProvenanceEdge['relation'];
  operation: string | null;
  evidence: ProvenanceEdge['evidence'];
  confidence: Confidence;
  sourceValue: string | null;
  targetValue: string | null;
  txnId: string;
  actorKind: string;
  actorUserId: string | null;
  correlationId: string | null;
  recordedAt: string;
}

export interface Lineage {
  direction: 'downstream' | 'upstream';
  maxDepth: number;
  truncated: boolean;
  edges: LineageEdge[];
}

export interface ProvenanceGap {
  target: VersionRef;
  field: string;
  requirement: string;
  operation: string;
  actorKind: string;
  changeEventId: string;
  occurredAt: string;
}

export interface FieldHistoryEntry {
  chainIndex: number;
  changeEventId: string;
  field: string;
  previousVersion: number | null;
  newVersion: number;
  previousValue: string | null;
  newValue: string | null;
  operation: string;
  actorKind: string;
  actorUserId: string | null;
  actorName: string | null;
  txnId: string;
  reason: string | null;
  correlationId: string | null;
  occurredAt: string;
}

/* ---------------------------------------------------------------- ATLAS X: conflicts */

export type ConflictKind =
  | 'NO_CONFLICT' | 'SAME_FIELD_CONFLICT' | 'DIFFERENT_FIELD_UPDATE' | 'SOURCE_VERSION_MISMATCH'
  | 'CONCURRENT_MODIFICATION' | 'UNKNOWN_CHANGE_ORIGIN' | 'REQUIRES_MANUAL_REVIEW';
export type ConflictRecommendation = 'CORRECT_AUTOMATICALLY' | 'PRESERVE_LATER_CHANGE' | 'HUMAN_REVIEW' | 'NONE';
export type ReviewDecision = 'KEEP_LATER_CHANGE' | 'RECOVERY_MAY_OVERWRITE' | 'NEEDS_INVESTIGATION';

export interface FieldConflict {
  key: EntityKey;
  field: string;
  kind: ConflictKind;
  taintedFrom: number;
  taintedTo: number;
  laterVersion: number | null;
  laterActorKind: string | null;
  laterFields: string[];
  recommendation: ConflictRecommendation;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  explanation: string;
}

export interface ConflictReview {
  entityType: string;
  entityId: string;
  field: string;
  kind: ConflictKind;
  decision: ReviewDecision;
  note: string;
  reviewedBy: string;
  reviewerName: string | null;
  reviewedAt: string;
}

export interface IncidentConflicts {
  incidentId: string;
  planId: string | null;
  open: number;
  conflicts: { conflict: FieldConflict; review: ConflictReview | null; open: boolean }[];
}

/* ---------------------------------------------------------------- ATLAS X: simulation and assessment */

export type StrategyName =
  | 'FULL_RESTORE' | 'NAIVE_FK_ROW' | 'VERSION_ROW_RESTORE' | 'FIELD_LEVEL_FK'
  | 'PROVENANCE_CONFLICT_BLIND' | 'PROVENANCE_CONFLICT_AWARE';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface StrategyResultRow {
  strategy: StrategyName;
  letter: string;
  applicable: boolean;
  notApplicableReason: string | null;
  recordsInspected: number;
  recordsModified: number;
  recordsDeleted: number;
  faultyRecords: number;
  repaired: number;
  collateralModified: number;
  legitimateOverwritten: number;
  conflictsSurfaced: number;
  conflictsIgnored: number;
  invariantViolations: number;
  fieldWrites: number;
  compensations: number;
  manualSteps: number;
  simulationMicros: number;
  violations: string[];
  notes: string[];
  /** Exact record-level totals (null for runs simulated before record details existed). */
  details: StrategyDetailTotals | null;
}

export interface StrategyDetailTotals {
  total: number; inspected: number; proposed: number; modified: number; preserved: number; conflicting: number; unresolved: number;
  preservedLaterChanges: number; unresolvedDependencies: number; stored: number; omitted: number; truncated: boolean;
}

export type RecordCategory = 'INSPECTED' | 'PROPOSED' | 'MODIFIED' | 'PRESERVED' | 'CONFLICTING' | 'UNRESOLVED';
export type RecordAction = 'DELETE' | 'MODIFY' | 'COMPENSATE' | 'REVIEW' | 'HELD' | 'NONE';

export interface StrategyRecordRow {
  seq: number; entityType: string; entityId: string; action: RecordAction; inspected: boolean; proposed: boolean;
  modified: boolean; preserved: boolean; conflicting: boolean; unresolved: boolean; fields: string[];
  provenancePath: string[]; conflictKind: string | null;
}

export interface StrategyRecordsPage {
  strategy: StrategyName; totals: StrategyDetailTotals | null; category: RecordCategory | null; storedMatching: number;
  partial: boolean; page: number; size: number; items: StrategyRecordRow[];
}

export interface StrategyComparisonRow {
  strategyA: StrategyName; strategyB: StrategyName; onlyA: number; onlyB: number; both: number; sameAction: number;
  totalDifferences: number; storedDifferences: number; omitted: number; truncated: boolean; sha256: string; createdAt: string;
  differences: { entityType: string; entityId: string; actionA: RecordAction; fieldsA: string[]; actionB: RecordAction; fieldsB: string[] }[] | null;
}

export interface OptimizerCandidate {
  strategy: StrategyName;
  feasible: boolean;
  rank: number;
  constraints: { code: string; description: string; passed: boolean; detail: string }[];
  score: { total: number; collateral: number; unresolved: number; risk: number; cost: number };
}

export interface RiskFactor {
  factor: string;
  level: RiskLevel;
  value: string;
  explanation: string;
}

export interface Assessment {
  candidates: OptimizerCandidate[];
  recommended: StrategyName | null;
  risk: RiskLevel;
  riskFactors: RiskFactor[];
  weights: { collateral: number; unresolved: number; risk: number; cost: number };
  summary: string;
}

export interface SimulationRun {
  id: string;
  incidentId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  snapshotChainIndex: number | null;
  fingerprint: string | null;
  records: number | null;
  versions: number | null;
  recommendedStrategy: StrategyName | null;
  riskLevel: RiskLevel | null;
  assessment: Assessment | null;
  failure: string | null;
  startedBy: string;
  startedByName: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
}

export interface SimulationView {
  run: SimulationRun;
  results: StrategyResultRow[];
}

export interface PlanAssessment {
  planId: string;
  planFingerprint: string;
  stale: boolean;
  staleReason: string | null;
  simulation: SimulationRun | null;
}

/* ---------------------------------------------------------------- ATLAS X: integrity */

export type IntegritySeverity = RiskLevel;
export type IntegrityStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'CLEARED';

export interface IntegrityRuleKind {
  kind: string;
  defaultCode: string;
  name: string;
  description: string;
  defaultSeverity: IntegritySeverity;
  defaultConfig: Record<string, unknown>;
}

export interface IntegrityRule {
  id: string;
  code: string;
  kind: string;
  name: string;
  severity: IntegritySeverity;
  enabled: boolean;
  config: Record<string, unknown>;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface IntegrityRuleVersion {
  version: number;
  kind: string;
  name: string;
  severity: IntegritySeverity;
  enabled: boolean;
  config: Record<string, unknown>;
  changedBy: string;
  changedByName: string | null;
  changedAt: string;
}

export interface IntegrityCheckResult {
  runId: string;
  status: 'COMPLETED' | 'FAILED';
  rulesEvaluated: number;
  findings: number;
  opened: number;
  cleared: number;
  failures: string[];
}

export interface IntegrityRun {
  id: string;
  trigger: 'SCHEDULED' | 'MANUAL';
  startedBy: string | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  rulesEvaluated: number;
  findings: number;
  opened: number;
  cleared: number;
  failure: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface IntegrityIncident {
  id: string;
  ruleId: string;
  ruleCode: string;
  ruleName: string;
  ruleVersion: number;
  subjectType: string;
  subjectId: string;
  severity: IntegritySeverity;
  status: IntegrityStatus;
  title: string;
  detail: Record<string, unknown>;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  closedAt: string | null;
  version: number;
}

export interface IntegrityIncidentDetail {
  incident: IntegrityIncident;
  history: { fromStatus: string | null; toStatus: string; actorUserId: string | null; actorName: string | null;
    note: string | null; runId: string | null; occurredAt: string }[];
}

/* ---------------------------------------------------------------- Graph Intelligence: schema */

export interface SchemaColumn { name: string; position: number; type: string; nullable: boolean; defaultValue: string | null }
export interface SchemaConstraint {
  name: string; kind: 'PRIMARY_KEY' | 'FOREIGN_KEY' | 'UNIQUE' | 'CHECK' | 'EXCLUSION'; columns: string[]; definition: string;
  references: string | null; referencedColumns: string[]; deferrable: boolean; validated: boolean;
}
export interface SchemaIndex { name: string; columns: string[]; unique: boolean; primary: boolean; predicate: string | null; definition: string }
export interface SchemaTrigger { name: string; timing: string; events: string[]; function: string }
export interface SchemaTable {
  name: string; kind: 'TABLE' | 'VIEW'; columns: SchemaColumn[]; constraints: SchemaConstraint[]; indexes: SchemaIndex[];
  triggers: SchemaTrigger[];
}
export type RelationshipKind = 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'AT_MOST_ONE_ACTIVE' | 'MANY_TO_MANY';
export interface SchemaRelationship {
  constraint: string; from: string; fromColumns: string[]; to: string; toColumns: string[]; kind: RelationshipKind;
  evidence: 'DECLARED' | 'INFERRED'; via: string | null; note: string;
}
export interface SchemaModel { tables: SchemaTable[]; relationships: SchemaRelationship[] }
export interface SchemaSnapshot {
  id: string; flywayVersion: string; sha256: string; tableCount: number; relationshipCount: number; capturedAt: string;
}
export interface LiveSchema {
  flywayVersion: string | null; sha256: string; schema: SchemaModel; latestSnapshot: SchemaSnapshot | null;
  changedSinceSnapshot: boolean;
}
export interface SchemaChange {
  objectType: string; table: string; name: string; change: 'ADDED' | 'REMOVED' | 'CHANGED'; before: string | null; after: string | null;
}
export interface SchemaDiffView { from: string; to: string; diff: { changes: SchemaChange[]; added: number; removed: number; changed: number } }
