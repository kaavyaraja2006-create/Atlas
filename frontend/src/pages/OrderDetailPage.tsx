import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, FileText, Plus, XCircle } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { customers, invoices, keys, orders, products } from '@/api/endpoints';
import type { Order } from '@/api/types';
import {
  Button, Card, ConfirmAction, ConflictBanner, EmptyState, ErrorState, Field, InlineError, Input, Select, Spinner, StatusBadge,
  Table, Td, Th,
} from '@/components/ui';
import { HistoryLink } from '@/components/HistoryLink';
import { dateTime, money, newIdempotencyKey } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

export function OrderDetailPage() {
  const { id = '' } = useParams();
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const currency = current?.currency ?? 'INR';
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const orderKey = keys.scoped(orgId, 'order', id);
  const order = useQuery({ queryKey: orderKey, queryFn: () => orders.get(id) });
  const customer = useQuery({
    queryKey: keys.scoped(orgId, 'customer', order.data?.customerId),
    queryFn: () => customers.get(order.data!.customerId),
    enabled: Boolean(order.data),
  });
  const linkedInvoices = useQuery({
    queryKey: keys.scoped(orgId, 'invoices', { orderId: id }),
    queryFn: () => invoices.list({ orderId: id, size: 20 }),
    enabled: order.data?.status === 'CONFIRMED',
  });

  const onChanged = async (next: Order) => {
    queryClient.setQueryData(orderKey, next);
    await queryClient.invalidateQueries({ queryKey: keys.scoped(orgId, 'orders') });
  };
  const confirm = useMutation({ mutationFn: (o: Order) => orders.confirm(o.id, o.version), onSuccess: onChanged });
  const cancel = useMutation({ mutationFn: (o: Order) => orders.cancel(o.id, o.version), onSuccess: onChanged });
  const [invoiceKey] = useState(() => newIdempotencyKey('invoice'));
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const invoice = useMutation({
    mutationFn: (o: Order) => orders.invoice(o.id, invoiceKey),
    onSuccess: (inv) => navigate(`/invoices/${inv.id}`),
  });

  if (order.isLoading) return <Spinner />;
  if (order.isError || !order.data) return <ErrorState error={order.error} onRetry={() => void order.refetch()} />;
  const o = order.data;
  const writable = can(Perm.orderWrite);
  const transitionError = confirm.error ?? cancel.error;
  const conflict = transitionError instanceof ApiError && transitionError.isConflict;
  const activeInvoice = linkedInvoices.data?.items.find((i) => i.status !== 'VOID');

  return (
    <>
      <Link to="/orders" className="mb-3 inline-flex items-center gap-1 text-[13px] text-muted hover:text-text">
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> Orders
      </Link>
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="font-mono text-xl font-semibold">{o.orderNumber}</h1>
            <StatusBadge status={o.status} />
          </div>
          <p className="mt-1 text-muted">
            {customer.data?.name ?? '…'} · created {dateTime(o.createdAt)}
            {o.placedAt && <> · confirmed {dateTime(o.placedAt)}</>}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <HistoryLink entityType="order" entityId={o.id} version={o.version} />
          {writable && o.status === 'DRAFT' && (
            <>
              <Button icon={<XCircle className="h-4 w-4" aria-hidden />} loading={cancel.isPending}
                onClick={() => setConfirmingCancel(true)}>
                Cancel order
              </Button>
              <Button variant="primary" icon={<CheckCircle2 className="h-4 w-4" aria-hidden />} loading={confirm.isPending}
                disabled={o.lines.length === 0} title={o.lines.length === 0 ? 'Add at least one line first' : undefined}
                onClick={() => confirm.mutate(o)}>
                Confirm order
              </Button>
            </>
          )}
          {o.status === 'CONFIRMED' && activeInvoice && (
            <Link to={`/invoices/${activeInvoice.id}`}>
              <Button icon={<FileText className="h-4 w-4" aria-hidden />}>View {activeInvoice.invoiceNumber}</Button>
            </Link>
          )}
          {o.status === 'CONFIRMED' && !activeInvoice && can(Perm.invoiceWrite) && !linkedInvoices.isLoading && (
            <Button variant="primary" icon={<FileText className="h-4 w-4" aria-hidden />} loading={invoice.isPending}
              onClick={() => invoice.mutate(o)}>
              Create invoice
            </Button>
          )}
        </div>
      </div>
      <div className="mb-4 space-y-2">
        {conflict ? <ConflictBanner onReload={() => { confirm.reset(); cancel.reset(); void order.refetch(); }} />
          : <InlineError error={transitionError} />}
        <InlineError error={invoice.error} />
      </div>
      <Card>
        {o.lines.length === 0 ? (
          <EmptyState title="No lines yet" body={o.status === 'DRAFT' ? 'Add products below.' : undefined} />
        ) : (
          <Table>
            <thead><tr><Th>#</Th><Th>Item</Th><Th align="right">Qty</Th><Th align="right">Unit price</Th><Th align="right">Line total</Th></tr></thead>
            <tbody>
              {o.lines.map((l) => (
                <tr key={l.id}>
                  <Td className="text-muted num">{l.lineNo}</Td>
                  <Td>{l.description}</Td>
                  <Td align="right">{l.quantity}</Td>
                  <Td align="right">{money(l.unitPrice, currency)}</Td>
                  <Td align="right" className="font-medium">{money(l.lineTotal, currency)}</Td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={4} className="px-4 py-3 text-right text-[13px] font-medium text-muted">Total</td>
                <td className="num px-4 py-3 text-right text-base font-semibold">{money(o.totalAmount, currency)}</td>
              </tr>
            </tfoot>
          </Table>
        )}
      </Card>
      {writable && o.status === 'DRAFT' && <AddLine order={o} onAdded={onChanged} />}
      <DeliveryNote order={o} writable={writable} onSaved={onChanged} onReload={() => void order.refetch()} />
      <ConfirmAction open={confirmingCancel} onClose={() => setConfirmingCancel(false)} title={`Cancel ${o.orderNumber}?`}
        body="A cancelled order can no longer take lines or be invoiced. This cannot be undone."
        confirmWord={o.orderNumber} confirmLabel="Cancel order" loading={cancel.isPending}
        onConfirm={() => cancel.mutate(o, { onSuccess: () => setConfirmingCancel(false) })} />
    </>
  );
}

function AddLine({ order, onAdded }: { order: Order; onAdded: (o: Order) => Promise<void> }) {
  const { current } = useSession();
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const catalogue = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'products', { active: true, size: 100, sort: 'sku' }),
    queryFn: () => products.list({ active: true, size: 100, sort: 'sku' }),
  });
  const add = useMutation({
    mutationFn: () => orders.addLine(order.id, productId, Number(quantity)),
    onSuccess: async (next) => { setQuantity('1'); await onAdded(next); },
  });
  const qty = Number(quantity);
  const validQty = Number.isInteger(qty) && qty >= 1 && qty <= 1_000_000;
  const selected = catalogue.data?.items.find((p) => p.id === productId);

  return (
    <Card className="mt-4 p-4">
      <h2 className="mb-3 font-semibold">Add a line</h2>
      <form className="grid items-end gap-3 sm:grid-cols-[1fr_8rem_auto]"
        onSubmit={(e) => { e.preventDefault(); if (productId && validQty) add.mutate(); }}>
        <Field label="Product" hint={selected ? `Priced at ${money(selected.unitPrice, current?.currency)} — the price is copied now and recorded with its version.` : undefined}>
          {(p) => (
            <Select {...p} value={productId} onChange={(e) => setProductId(e.target.value)}>
              <option value="" disabled>{catalogue.isLoading ? 'Loading…' : 'Choose a product…'}</option>
              {catalogue.data?.items.map((pr) => <option key={pr.id} value={pr.id}>{pr.sku} — {pr.name}</option>)}
            </Select>
          )}
        </Field>
        <Field label="Quantity" error={quantity && !validQty ? '1 to 1,000,000' : undefined}>
          {(p) => <Input {...p} inputMode="numeric" value={quantity} onChange={(e) => setQuantity(e.target.value)} />}
        </Field>
        <Button type="submit" variant="primary" icon={<Plus className="h-4 w-4" aria-hidden />} loading={add.isPending}
          disabled={!productId || !validQty}>
          Add line
        </Button>
      </form>
      <div className="mt-3"><InlineError error={add.error} /></div>
    </Card>
  );
}

/**
 * A free-text field anyone with order.write may change while the order is not cancelled.
 * It is the "legitimate later update" a recovery must not overwrite: it lives on the same
 * row as the total, so a whole-row restore would erase it.
 */
function DeliveryNote({ order, writable, onSaved, onReload }: {
  order: Order; writable: boolean; onSaved: (o: Order) => Promise<void>; onReload: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(order.deliveryNote ?? '');
  const save = useMutation({
    mutationFn: () => orders.updateDeliveryNote(order.id, order.version, text.trim() === '' ? null : text),
    onSuccess: async (next) => { setEditing(false); await onSaved(next); },
  });
  const conflict = save.error instanceof ApiError && save.error.isConflict;
  const canEdit = writable && order.status !== 'CANCELLED';
  return (
    <Card className="mt-4 p-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-semibold">Delivery note</h2>
        {canEdit && !editing && (
          <Button size="sm" onClick={() => { setText(order.deliveryNote ?? ''); save.reset(); setEditing(true); }}>
            {order.deliveryNote ? 'Edit' : 'Add note'}
          </Button>
        )}
      </div>
      {editing ? (
        <form className="mt-3 space-y-3" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <Field label="Note" hint="Up to 500 characters. Every change is audited." error={text.length > 500 ? 'Too long' : undefined}>
            {(p) => <Input {...p} value={text} onChange={(e) => setText(e.target.value)} />}
          </Field>
          {conflict ? <ConflictBanner onReload={() => { setEditing(false); save.reset(); onReload(); }} /> : <InlineError error={save.error} />}
          <div className="flex gap-2">
            <Button type="submit" variant="primary" loading={save.isPending} disabled={text.length > 500}>Save note</Button>
            <Button type="button" variant="ghost" onClick={() => setEditing(false)}>Cancel</Button>
          </div>
        </form>
      ) : (
        <p className="mt-2 text-[13px] text-muted">{order.deliveryNote ?? 'No delivery note.'}</p>
      )}
    </Card>
  );
}
