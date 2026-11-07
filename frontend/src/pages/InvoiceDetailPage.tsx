import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Ban, Send } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { invoices, keys, orders } from '@/api/endpoints';
import type { Invoice } from '@/api/types';
import {
  Button, Card, ConfirmAction, ConflictBanner, ErrorState, Field, InlineError, Input, Spinner, StatusBadge, Table, Td, Th,
} from '@/components/ui';
import { HistoryLink } from '@/components/HistoryLink';
import { dateTime, money } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

export function InvoiceDetailPage() {
  const { id = '' } = useParams();
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const currency = current?.currency ?? 'INR';
  const queryClient = useQueryClient();
  const invoiceKey = keys.scoped(orgId, 'invoice', id);
  const notesKey = keys.scoped(orgId, 'credit-notes', id);
  const invoice = useQuery({ queryKey: invoiceKey, queryFn: () => invoices.get(id) });
  const notes = useQuery({ queryKey: notesKey, queryFn: () => invoices.creditNotes(id), enabled: invoice.isSuccess });
  const order = useQuery({
    queryKey: keys.scoped(orgId, 'order', invoice.data?.orderId),
    queryFn: () => orders.get(invoice.data!.orderId),
    enabled: invoice.isSuccess,
  });
  const [voiding, setVoiding] = useState(false);
  const [voidReason, setVoidReason] = useState('');

  const onChanged = async (next: Invoice) => {
    queryClient.setQueryData(invoiceKey, next);
    await queryClient.invalidateQueries({ queryKey: keys.scoped(orgId, 'invoices') });
  };
  const issue = useMutation({ mutationFn: (i: Invoice) => invoices.issue(i.id, i.version), onSuccess: onChanged });
  const voidIt = useMutation({
    mutationFn: (i: Invoice) => invoices.void(i.id, i.version, voidReason.trim() || undefined),
    onSuccess: async (next) => { setVoiding(false); await onChanged(next); },
  });

  if (invoice.isLoading) return <Spinner />;
  if (invoice.isError || !invoice.data) return <ErrorState error={invoice.error} onRetry={() => void invoice.refetch()} />;
  const inv = invoice.data;
  const writable = can(Perm.invoiceWrite);
  const credited = (notes.data ?? []).reduce((sum, n) => sum + n.amount, 0);
  const conflict = issue.error instanceof ApiError && issue.error.isConflict;

  return (
    <>
      <Link to="/invoices" className="mb-3 inline-flex items-center gap-1 text-[13px] text-muted hover:text-text">
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> Invoices
      </Link>
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="font-mono text-xl font-semibold">{inv.invoiceNumber}</h1>
            <StatusBadge status={inv.status} />
          </div>
          <p className="mt-1 text-muted">
            For order{' '}
            {order.data ? <Link className="font-mono text-accent hover:underline" to={`/orders/${inv.orderId}`}>{order.data.orderNumber}</Link> : '…'}
            {' '}· issued {dateTime(inv.issuedAt)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <HistoryLink entityType="invoice" entityId={inv.id} version={inv.version} />
          {writable && (<>
            {inv.status !== 'VOID' && (notes.data?.length ?? 0) === 0 && (
              <Button icon={<Ban className="h-4 w-4" aria-hidden />} onClick={() => setVoiding(true)}>
                {inv.status === 'DRAFT' ? 'Discard draft' : 'Void invoice'}
              </Button>
            )}
            {inv.status === 'DRAFT' && (
              <Button variant="primary" icon={<Send className="h-4 w-4" aria-hidden />} loading={issue.isPending}
                onClick={() => issue.mutate(inv)}>
                Issue invoice
              </Button>
            )}
          </>)}
        </div>
      </div>
      <div className="mb-4">
        {conflict ? <ConflictBanner onReload={() => { issue.reset(); void invoice.refetch(); }} /> : <InlineError error={issue.error} />}
      </div>

      <Card>
        <Table>
          <thead><tr><Th>#</Th><Th>Item</Th><Th align="right">Qty</Th><Th align="right">Unit price</Th><Th align="right">Line total</Th></tr></thead>
          <tbody>
            {inv.lines.map((l) => (
              <tr key={l.id}>
                <Td className="num text-muted">{l.lineNo}</Td>
                <Td>{l.description}</Td>
                <Td align="right">{l.quantity}</Td>
                <Td align="right">{money(l.unitPrice, currency)}</Td>
                <Td align="right" className="font-medium">{money(l.lineTotal, currency)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <dl className="ml-auto grid max-w-xs grid-cols-2 gap-x-6 gap-y-1.5 px-4 py-4 text-[13px]">
          <dt className="text-muted">Invoice total</dt><dd className="num text-right font-medium">{money(inv.totalAmount, currency)}</dd>
          <dt className="text-muted">Credited</dt><dd className="num text-right">{credited ? `− ${money(credited, currency)}` : '—'}</dd>
          <dt className="font-medium">Amount due</dt>
          <dd className="num text-right text-base font-semibold">{money(inv.status === 'VOID' ? 0 : inv.totalAmount - credited, currency)}</dd>
        </dl>
      </Card>

      <CreditNotes invoice={inv} notes={notes.data ?? []} loading={notes.isLoading} canCredit={writable && inv.status === 'ISSUED'}
        remaining={inv.totalAmount - credited}
        onCredited={() => queryClient.invalidateQueries({ queryKey: notesKey })} />

      <ConfirmAction open={voiding} onClose={() => { setVoiding(false); voidIt.reset(); }}
        title={inv.status === 'DRAFT' ? `Discard ${inv.invoiceNumber}?` : `Void ${inv.invoiceNumber}?`}
        body={inv.status === 'DRAFT'
          ? 'The draft is kept in history as VOID and the order can be invoiced again.'
          : 'A void invoice stays in history but is no longer payable. This cannot be undone.'}
        confirmWord={inv.invoiceNumber} confirmLabel={inv.status === 'DRAFT' ? 'Discard draft' : 'Void invoice'}
        loading={voidIt.isPending} error={voidIt.error} onConfirm={() => voidIt.mutate(inv)}>
        <Field label="Reason (optional)">
          {(p) => <Input {...p} value={voidReason} onChange={(e) => setVoidReason(e.target.value)} />}
        </Field>
      </ConfirmAction>
    </>
  );
}

function CreditNotes({ invoice, notes, loading, canCredit, remaining, onCredited }: {
  invoice: Invoice; notes: { id: string; creditNoteNumber: string; amount: number; reason: string; issuedAt: string | null }[];
  loading: boolean; canCredit: boolean; remaining: number; onCredited: () => Promise<void>;
}) {
  const { current } = useSession();
  const schema = z.object({
    amount: z.string().trim().regex(/^\d{1,15}(\.\d{1,4})?$/, 'Enter an amount')
      .refine((v) => Number(v) > 0, 'Must be greater than zero')
      .refine((v) => Number(v) <= remaining + 1e-9, `At most ${money(remaining, current?.currency)}`),
    reason: z.string().trim().min(1, 'Say why').max(500),
  });
  type Values = z.infer<typeof schema>;
  const { register, handleSubmit, formState, reset } = useForm<Values>({ resolver: zodResolver(schema) });
  const credit = useMutation({
    mutationFn: (v: Values) => invoices.credit(invoice.id, v.amount, v.reason),
    onSuccess: async () => { reset(); await onCredited(); },
  });

  return (
    <Card className="mt-4">
      <div className="border-b border-border px-4 py-3"><h2 className="font-semibold">Credit notes</h2></div>
      {loading ? <Spinner /> : notes.length === 0 ? (
        <p className="px-4 py-4 text-muted">No credit notes.</p>
      ) : (
        <Table>
          <thead><tr><Th>Number</Th><Th>Reason</Th><Th align="right">Amount</Th><Th>Issued</Th></tr></thead>
          <tbody>
            {notes.map((n) => (
              <tr key={n.id}>
                <Td className="font-mono text-[12px]">{n.creditNoteNumber}</Td>
                <Td>{n.reason}</Td>
                <Td align="right">{money(n.amount, current?.currency)}</Td>
                <Td className="whitespace-nowrap text-muted">{dateTime(n.issuedAt)}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
      {canCredit && remaining > 0 && (
        <form noValidate onSubmit={handleSubmit((v) => credit.mutate(v))}
          className="grid items-end gap-3 border-t border-border p-4 sm:grid-cols-[10rem_1fr_auto]">
          <Field label="Amount" error={formState.errors.amount?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...register('amount')} />}
          </Field>
          <Field label="Reason" error={formState.errors.reason?.message}>
            {(p) => <Input {...p} {...register('reason')} />}
          </Field>
          <Button type="submit" variant="primary" loading={credit.isPending}>Issue credit note</Button>
          <div className="sm:col-span-3"><InlineError error={credit.error} /></div>
        </form>
      )}
    </Card>
  );
}
