import { History } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui';
import { historyPath } from '@/lib/audit';
import { Perm, useSession } from '@/session/SessionProvider';

/** Shown only to people who may read the audit history. */
export function HistoryLink({ entityType, entityId, version }: { entityType: string; entityId: string; version?: number }) {
  const { can } = useSession();
  if (!can(Perm.auditRead)) return null;
  return (
    <Link to={historyPath(entityType, entityId, version)}>
      <Button size="sm" variant="ghost" icon={<History className="h-3.5 w-3.5" aria-hidden />}>History</Button>
    </Link>
  );
}
