import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { customers, keys, orders } from '@/api/endpoints';
import type { OrderStatus } from '@/api/types';
import {
  Button, Card, EmptyState, ErrorState, Field, InlineError, Modal, PageHeader, Pagination, Select, Spinner,
  StatusBadge, Table, Td, Th,
} from '@/components/ui';
import { dateTime, money, newIdempotencyKey } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

export function OrdersPage() {
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<'' | OrderStatus>('');
  const [creating, setCreating] = useState(false);
  const params = { page, size: 20, status: status || undefined };
  const list = useQuery({
    queryKey: keys.scoped(orgId, 'orders', params),
    queryFn: () => orders.list(params),
    placeholderData: keepPreviousData,
  });
  const customerList = useQuery({
    queryKey: keys.scoped(orgId, 'customers', { page: 0, size: 100, sort: 'name' }),
    queryFn: () => customers.list({ page: 0, size: 100, sort: 'name' }),
  });
  const customerName = useMemo(
    () => new Map((customerList.data?.items ?? []).map((c) => [c.id, c.name])),
    [customerList.data],
  );

  return (
    <>
      <PageHeader title="Orders" description="Draft orders take lines; confirmed orders can be invoiced."
        actions={can(Perm.orderWrite) && (
          <Button variant="primary" icon={<Plus className="h-4 w-4" aria-hidden />} onClick={() => setCreating(true)}>New order</Button>
        )} />
      <Card>
        <div className="border-b border-border p-3">
          <Select aria-label="Filter by status" className="w-auto" value={status}
            onChange={(e) => { setStatus(e.target.value as typeof status); setPage(0); }}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="CONFIRMED">Confirmed</option>
            <option value="CANCELLED">Cancelled</option>
          </Select>
        </div>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? <EmptyState title={status ? 'No orders with this status' : 'No orders yet'} /> :
          list.data && (
            <>
              <Table>
                <thead><tr><Th>Order</Th><Th>Customer</Th><Th>Status</Th><Th align="right">Total</Th><Th>Created</Th></tr></thead>
                <tbody>
                  {list.data.items.map((o) => (
                    <tr key={o.id} className="hover:bg-surface-2">
                      <Td><Link to={`/orders/${o.id}`} className="whitespace-nowrap font-mono text-[12px] font-medium text-accent hover:underline">{o.orderNumber}</Link></Td>
                      <Td>{customerName.get(o.customerId) ?? '—'}</Td>
                      <Td><StatusBadge status={o.status} /></Td>
                      <Td align="right">{money(o.totalAmount, current?.currency)}</Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(o.createdAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
      {creating && <NewOrderModal onClose={() => setCreating(false)} customerOptions={customerList.data?.items ?? []} />}
    </>
  );
}

function NewOrderModal({ onClose, customerOptions }: { onClose: () => void; customerOptions: { id: string; name: string }[] }) {
  const navigate = useNavigate();
  const [customerId, setCustomerId] = useState('');
  // One key per opened dialog: a double click or retried request cannot create two orders.
  const [idempotencyKey] = useState(() => newIdempotencyKey('order'));
  const create = useMutation({
    mutationFn: () => orders.create(customerId, idempotencyKey),
    onSuccess: (order) => navigate(`/orders/${order.id}`),
  });
  return (
    <Modal open onClose={onClose} title="New order"
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!customerId} loading={create.isPending} onClick={() => create.mutate()}>Create draft order</Button>
      </>}>
      {customerOptions.length === 0 ? (
        <EmptyState title="No customers yet" body="Add a customer first."
          action={<Link className="font-medium text-accent hover:underline" to="/customers">Go to customers</Link>} />
      ) : (
        <div className="space-y-4">
          <Field label="Customer">
            {(p) => (
              <Select {...p} value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
                <option value="" disabled>Choose a customer…</option>
                {customerOptions.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>
            )}
          </Field>
          <InlineError error={create.error} />
        </div>
      )}
    </Modal>
  );
}
