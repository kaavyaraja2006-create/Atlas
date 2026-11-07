import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { keys, recovery } from '@/api/endpoints';
import type { IncidentStatus } from '@/api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, Pagination, Spinner, Table, Td, Th } from '@/components/ui';
import { dateTime } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

const incidentTone: Record<IncidentStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  OPEN: 'danger',
  PLANNED: 'info',
  APPROVED: 'info',
  EXECUTING: 'warning',
  RESOLVED: 'success',
  CANCELLED: 'neutral',
};

export function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  return <Badge tone={incidentTone[status]}>{status}</Badge>;
}

export function RecoveryPage() {
  const { current } = useSession();
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'incidents', page),
    queryFn: () => recovery.incidents({ page, size: 20 }),
  });

  return (
    <>
      <PageHeader title="Recovery"
        description="Incidents name one change that was wrong. PASCAR finds what consumed it, plans the repair, and checks the result." />
      <Card>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : list.data!.items.length === 0 ? (
            <EmptyState title="No incidents"
              body="To open one, find the faulty change in the Audit log, open it, and choose “Open recovery incident”." />
          ) : (
            <>
              <Table>
                <thead><tr><Th>Incident</Th><Th>Title</Th><Th>Status</Th><Th>Opened</Th></tr></thead>
                <tbody>
                  {list.data!.items.map((i) => (
                    <tr key={i.id}>
                      <Td><Link className="font-mono text-accent hover:underline" to={`/recovery/${i.id}`}>{i.incidentNumber}</Link></Td>
                      <Td>{i.title}</Td>
                      <Td><IncidentStatusBadge status={i.status} /></Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(i.openedAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data!.page.totalPages} totalItems={list.data!.page.totalItems}
                onPage={setPage} />
            </>
          )}
      </Card>
    </>
  );
}
