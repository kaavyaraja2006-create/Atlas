import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { invoices, keys } from '@/api/endpoints';
import type { InvoiceStatus } from '@/api/types';
import { Card, EmptyState, ErrorState, PageHeader, Pagination, Select, Spinner, StatusBadge, Table, Td, Th } from '@/components/ui';
import { dateTime, money } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

export function InvoicesPage() {
  const { current } = useSession();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<'' | InvoiceStatus>('');
  const params = { page, size: 20, status: status || undefined };
  const list = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'invoices', params),
    queryFn: () => invoices.list(params),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader title="Invoices" description="Issued invoices are final; corrections are made with credit notes." />
      <Card>
        <div className="border-b border-border p-3">
          <Select aria-label="Filter by status" className="w-auto" value={status}
            onChange={(e) => { setStatus(e.target.value as typeof status); setPage(0); }}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="ISSUED">Issued</option>
            <option value="VOID">Void</option>
          </Select>
        </div>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? (
            <EmptyState title={status ? 'No invoices with this status' : 'No invoices yet'}
              body={status ? undefined : 'Invoices are created from confirmed orders.'} />
          ) : list.data && (
            <>
              <Table>
                <thead><tr><Th>Invoice</Th><Th>Status</Th><Th align="right">Total</Th><Th>Issued</Th><Th>Created</Th></tr></thead>
                <tbody>
                  {list.data.items.map((i) => (
                    <tr key={i.id} className="hover:bg-surface-2">
                      <Td><Link to={`/invoices/${i.id}`} className="whitespace-nowrap font-mono text-[12px] font-medium text-accent hover:underline">{i.invoiceNumber}</Link></Td>
                      <Td><StatusBadge status={i.status} /></Td>
                      <Td align="right">{money(i.totalAmount, current?.currency)}</Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(i.issuedAt)}</Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(i.createdAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
    </>
  );
}
