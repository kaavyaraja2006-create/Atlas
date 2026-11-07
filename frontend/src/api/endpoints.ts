import { api, query } from './client';
import type {
  RecordCategory, StrategyComparisonRow, StrategyRecordsPage,
  LiveSchema, SchemaDiffView, SchemaSnapshot,
  Access,
  AuditEvent,
  ChainVerification,
  ProvenanceEdge,
  CreditNote,
  Customer,
  Decision,
  ExecutionReport,
  ExecutionView,
  ImpactView,
  Incident,
  Invoice,
  InvoiceStatus,
  Me,
  Member,
  Order,
  OrderStatus,
  Organization,
  Page,
  Plan,
  Product,
  VerificationRow,
  ConflictReview,
  FieldHistoryEntry,
  IncidentConflicts,
  IntegrityCheckResult,
  IntegrityIncident,
  IntegrityIncidentDetail,
  IntegrityRule,
  IntegrityRuleKind,
  IntegrityRuleVersion,
  IntegrityRun,
  IntegritySeverity,
  IntegrityStatus,
  Lineage,
  PlanAssessment,
  ProvenanceGap,
  ReviewDecision,
  SimulationRun,
  SimulationView,
} from './types';

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
}

export const auth = {
  register: (body: { email: string; displayName: string; password: string }) =>
    api.request<{ id: string; email: string }>('/api/v1/auth/register', { method: 'POST', body, untenanted: true }),
  me: () => api.request<Me>('/api/v1/users/me', { untenanted: true }),
};

export const organizations = {
  mine: () => api.request<Organization[]>('/api/v1/organizations', { untenanted: true }),
  create: (body: { name: string; slug: string }) =>
    api.request<Organization>('/api/v1/organizations', { method: 'POST', body, untenanted: true }),
  access: () => api.request<Access>('/api/v1/access'),
};

export const members = {
  list: (p: PageParams = {}) => api.request<Page<Member>>(`/api/v1/members${query({ ...p })}`),
  add: (body: { email: string; roles: string[] }) =>
    api.request<Member>('/api/v1/members', { method: 'POST', body }),
  setRoles: (userId: string, roles: string[]) =>
    api.request<Member>(`/api/v1/members/${userId}/roles`, { method: 'PUT', body: { roles } }),
};

export const products = {
  list: (p: PageParams & { q?: string; active?: boolean }) =>
    api.request<Page<Product>>(`/api/v1/products${query({ ...p })}`),
  get: (id: string) => api.request<Product>(`/api/v1/products/${id}`),
  create: (body: { sku: string; name: string; unitPrice: string; unitCost: string; active: boolean }) =>
    api.request<Product>('/api/v1/products', { method: 'POST', body }),
  update: (
    id: string,
    version: number,
    body: { name?: string; unitPrice?: string; unitCost?: string; active?: boolean; reason?: string },
  ) => api.request<Product>(`/api/v1/products/${id}`, { method: 'PATCH', body, ifMatchVersion: version }),
};

export const customers = {
  list: (p: PageParams & { q?: string }) => api.request<Page<Customer>>(`/api/v1/customers${query({ ...p })}`),
  get: (id: string) => api.request<Customer>(`/api/v1/customers/${id}`),
  create: (body: { name: string; email?: string }) =>
    api.request<Customer>('/api/v1/customers', { method: 'POST', body }),
};

export const orders = {
  list: (p: PageParams & { status?: OrderStatus; customerId?: string }) =>
    api.request<Page<Order>>(`/api/v1/orders${query({ ...p })}`),
  get: (id: string) => api.request<Order>(`/api/v1/orders/${id}`),
  create: (customerId: string, idempotencyKey: string) =>
    api.request<Order>('/api/v1/orders', { method: 'POST', body: { customerId }, idempotencyKey }),
  addLine: (id: string, productId: string, quantity: number) =>
    api.request<Order>(`/api/v1/orders/${id}/lines`, { method: 'POST', body: { productId, quantity } }),
  confirm: (id: string, version: number) =>
    api.request<Order>(`/api/v1/orders/${id}/confirm`, { method: 'POST', ifMatchVersion: version }),
  cancel: (id: string, version: number) =>
    api.request<Order>(`/api/v1/orders/${id}/cancel`, { method: 'POST', ifMatchVersion: version }),
  invoice: (id: string, idempotencyKey: string) =>
    api.request<Invoice>(`/api/v1/orders/${id}/invoice`, { method: 'POST', idempotencyKey }),
  updateDeliveryNote: (id: string, version: number, deliveryNote: string | null) =>
    api.request<Order>(`/api/v1/orders/${id}`, { method: 'PATCH', body: { deliveryNote }, ifMatchVersion: version }),
};

export const invoices = {
  list: (p: PageParams & { status?: InvoiceStatus; orderId?: string }) =>
    api.request<Page<Invoice>>(`/api/v1/invoices${query({ ...p })}`),
  get: (id: string) => api.request<Invoice>(`/api/v1/invoices/${id}`),
  issue: (id: string, version: number) =>
    api.request<Invoice>(`/api/v1/invoices/${id}/issue`, { method: 'POST', ifMatchVersion: version }),
  void: (id: string, version: number, reason?: string) =>
    api.request<Invoice>(`/api/v1/invoices/${id}/void`, {
      method: 'POST',
      ifMatchVersion: version,
      body: reason ? { reason } : {},
    }),
  creditNotes: (id: string) => api.request<CreditNote[]>(`/api/v1/invoices/${id}/credit-notes`),
  credit: (id: string, amount: string, reason: string) =>
    api.request<CreditNote>(`/api/v1/invoices/${id}/credit-notes`, { method: 'POST', body: { amount, reason } }),
};

export interface AuditFilter extends PageParams {
  entityType?: string;
  entityId?: string;
  actorUserId?: string;
  txnId?: string;
  from?: string;
  to?: string;
}

export const audit = {
  list: (f: AuditFilter) => api.request<Page<AuditEvent>>(`/api/v1/audit/events${query({ ...f })}`),
  get: (id: string) => api.request<AuditEvent>(`/api/v1/audit/events/${id}`),
  verify: () => api.request<ChainVerification>('/api/v1/audit/verify', { method: 'POST' }),
};

export const provenance = {
  edges: (entityType: string, entityId: string, direction: 'upstream' | 'downstream', version?: number) =>
    api.request<ProvenanceEdge[]>(
      `/api/v1/provenance/${encodeURIComponent(entityType)}/${entityId}${query({ direction, version })}`,
    ),
  lineage: (entityType: string, entityId: string, direction: 'upstream' | 'downstream', depth: number, version?: number) =>
    api.request<Lineage>(
      `/api/v1/provenance/${encodeURIComponent(entityType)}/${entityId}/lineage${query({ direction, depth, version })}`,
    ),
  gaps: (entityType?: string) => api.request<ProvenanceGap[]>(`/api/v1/provenance/gaps${query({ entityType })}`),
};

export const history = {
  fields: (entityType: string, entityId: string, field?: string) =>
    api.request<FieldHistoryEntry[]>(`/api/v1/history/${encodeURIComponent(entityType)}/${entityId}/fields${query({ field })}`),
};

export const integrity = {
  kinds: () => api.request<IntegrityRuleKind[]>('/api/v1/integrity/rule-kinds'),
  rules: () => api.request<IntegrityRule[]>('/api/v1/integrity/rules'),
  createRule: (kind: string) => api.request<IntegrityRule>('/api/v1/integrity/rules', { method: 'POST', body: { kind } }),
  updateRule: (r: IntegrityRule, change: Partial<Pick<IntegrityRule, 'enabled' | 'severity' | 'name'>>) =>
    api.request<IntegrityRule>(`/api/v1/integrity/rules/${r.id}`, {
      method: 'PUT', ifMatchVersion: r.version,
      body: { name: change.name ?? r.name, severity: change.severity ?? r.severity, enabled: change.enabled ?? r.enabled, config: r.config },
    }),
  versions: (id: string) => api.request<IntegrityRuleVersion[]>(`/api/v1/integrity/rules/${id}/versions`),
  check: () => api.request<IntegrityCheckResult>('/api/v1/integrity/checks', { method: 'POST' }),
  checks: () => api.request<IntegrityRun[]>('/api/v1/integrity/checks'),
  incidents: (p: PageParams & { status?: IntegrityStatus; severity?: IntegritySeverity }) =>
    api.request<Page<IntegrityIncident>>(`/api/v1/integrity/incidents${query({ ...p })}`),
  incident: (id: string) => api.request<IntegrityIncidentDetail>(`/api/v1/integrity/incidents/${id}`),
  transition: (i: IntegrityIncident, status: 'ACKNOWLEDGED' | 'RESOLVED', note: string) =>
    api.request<IntegrityIncidentDetail>(`/api/v1/integrity/incidents/${i.id}/transitions`, {
      method: 'POST', ifMatchVersion: i.version, body: { status, note },
    }),
};

export const recovery = {
  incidents: (p: PageParams = {}) => api.request<Page<Incident>>(`/api/v1/recovery/incidents${query({ ...p })}`),
  incident: (id: string) => api.request<Incident>(`/api/v1/recovery/incidents/${id}`),
  open: (body: { changeEventId: string; title: string; reason: string }) =>
    api.request<Incident>('/api/v1/recovery/incidents', { method: 'POST', body }),
  impact: (id: string) => api.request<ImpactView>(`/api/v1/recovery/incidents/${id}/impact`, { method: 'POST' }),
  plans: (incidentId: string) => api.request<Plan[]>(`/api/v1/recovery/incidents/${incidentId}/plans`),
  generate: (incidentId: string) =>
    api.request<Plan>(`/api/v1/recovery/incidents/${incidentId}/plans`, { method: 'POST' }),
  approve: (planId: string, fingerprint: string, comment?: string) =>
    api.request<Decision>(`/api/v1/recovery/plans/${planId}/approval`, { method: 'POST', body: { fingerprint, comment } }),
  reject: (planId: string, comment: string) =>
    api.request<Decision>(`/api/v1/recovery/plans/${planId}/rejection`, { method: 'POST', body: { comment } }),
  execute: (planId: string) =>
    api.request<ExecutionReport>(`/api/v1/recovery/plans/${planId}/execution`, { method: 'POST' }),
  execution: (planId: string) => api.request<ExecutionView>(`/api/v1/recovery/plans/${planId}/execution`),
  acknowledge: (planId: string, stepNo: number, note: string) =>
    api.request<unknown>(`/api/v1/recovery/plans/${planId}/steps/${stepNo}/acknowledgement`, { method: 'POST', body: { note } }),
  verify: (planId: string) => api.request<unknown>(`/api/v1/recovery/plans/${planId}/verifications`, { method: 'POST' }),
  verifications: (planId: string) => api.request<VerificationRow[]>(`/api/v1/recovery/plans/${planId}/verifications`),
  conflicts: (incidentId: string) => api.request<IncidentConflicts>(`/api/v1/recovery/incidents/${incidentId}/conflicts`),
  reviewConflict: (incidentId: string, body: { entityType: string; entityId: string; field: string;
    decision: ReviewDecision; note: string }) =>
    api.request<ConflictReview>(`/api/v1/recovery/incidents/${incidentId}/conflicts/reviews`, { method: 'POST', body }),
  simulate: (incidentId: string) =>
    api.request<SimulationView>(`/api/v1/recovery/incidents/${incidentId}/simulations`, { method: 'POST' }),
  simulations: (incidentId: string) => api.request<SimulationRun[]>(`/api/v1/recovery/incidents/${incidentId}/simulations`),
  simulation: (id: string) => api.request<SimulationView>(`/api/v1/recovery/simulations/${id}`),
  assessment: (planId: string) => api.request<PlanAssessment>(`/api/v1/recovery/plans/${planId}/assessment`),
  strategyRecords: (runId: string, strategy: string, p: { category?: RecordCategory; page?: number; size?: number }) =>
    api.request<StrategyRecordsPage>(`/api/v1/recovery/simulations/${runId}/strategies/${strategy}/records${query({ ...p })}`),
  comparisons: (runId: string) => api.request<StrategyComparisonRow[]>(`/api/v1/recovery/simulations/${runId}/comparisons`),
  comparison: (runId: string, a: string, b: string) =>
    api.request<StrategyComparisonRow>(`/api/v1/recovery/simulations/${runId}/comparisons/${a}/${b}`),
};

/** Query keys are always scoped by organization so one tenant's cache never shows in another. */
/** ER Schema Explorer: platform administrators only (the server decides and audits every call). */
export const schema = {
  live: () => api.request<LiveSchema>('/api/v1/graph/schema', { untenanted: true }),
  versions: () => api.request<SchemaSnapshot[]>('/api/v1/graph/schema/versions', { untenanted: true }),
  diff: (from: string, to = 'live') => api.request<SchemaDiffView>(`/api/v1/graph/schema/diff${query({ from, to })}`, { untenanted: true }),
  recordExport: (format: 'SVG' | 'PNG', version: string, tables: number) =>
    api.request<{ auditEventId: string }>('/api/v1/graph/schema/exports',
      { method: 'POST', body: { format, version, tables }, untenanted: true }),
};

export const keys = {
  orgs: ['organizations'] as const,
  me: ['me'] as const,
  scoped: (orgId: string | null, ...rest: unknown[]) => ['org', orgId, ...rest] as const,
};
