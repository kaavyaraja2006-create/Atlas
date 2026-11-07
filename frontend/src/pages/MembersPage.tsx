import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
import { useState } from 'react';
import { keys, members } from '@/api/endpoints';
import type { Member } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, ErrorState, Field, InlineError, Input, Modal, PageHeader, Pagination, Spinner, Table, Td, Th,
} from '@/components/ui';
import { dateTime } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

/** Role codes and what they mean; must match roles seeded in V1__tenancy_and_identity.sql. */
export const ROLES: { code: string; label: string; description: string }[] = [
  { code: 'ORGANIZATION_ADMIN', label: 'Organization admin', description: 'Members, roles and all business records' },
  { code: 'OPERATIONS_MANAGER', label: 'Operations manager', description: 'Catalogue, prices, orders and invoices' },
  { code: 'SALES_STAFF', label: 'Sales staff', description: 'Customers and orders' },
  { code: 'AUDITOR', label: 'Auditor', description: 'Read-only, including audit history' },
  { code: 'RECOVERY_APPROVER', label: 'Recovery approver', description: 'Approves recovery plans' },
  { code: 'EMPLOYEE', label: 'Employee', description: 'Read-only access to business records' },
];

export function MembersPage() {
  const { current, me } = useSession();
  const orgId = current?.id ?? null;
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Member | null>(null);
  const list = useQuery({ queryKey: keys.scoped(orgId, 'members', page), queryFn: () => members.list({ page, size: 20 }) });

  return (
    <>
      <PageHeader title="Members" description="Who can work in this organization, and what they may do."
        actions={<Button variant="primary" icon={<UserPlus className="h-4 w-4" aria-hidden />} onClick={() => setAdding(true)}>Add member</Button>} />
      <Card>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? <EmptyState title="No members" /> : list.data && (
            <>
              <Table>
                <thead><tr><Th>Name</Th><Th>Email</Th><Th>Roles</Th><Th>Joined</Th><Th /></tr></thead>
                <tbody>
                  {list.data.items.map((m) => (
                    <tr key={m.userId}>
                      <Td className="font-medium">{m.displayName}{m.userId === me?.id && <span className="ml-2 text-[12px] text-muted">(you)</span>}</Td>
                      <Td className="text-muted">{m.email}</Td>
                      <Td><div className="flex flex-wrap gap-1">{m.roles.map((r) => <Badge key={r}>{r.replace(/_/g, ' ')}</Badge>)}</div></Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(m.joinedAt)}</Td>
                      <Td align="right"><Button size="sm" variant="ghost" onClick={() => setEditing(m)}>Edit roles</Button></Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
      {adding && <MemberDialog onClose={() => setAdding(false)} />}
      {editing && <MemberDialog member={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function MemberDialog({ member, onClose }: { member?: Member; onClose: () => void }) {
  const { current } = useSession();
  const queryClient = useQueryClient();
  const [email, setEmail] = useState('');
  const [roles, setRoles] = useState<string[]>(member?.roles ?? ['EMPLOYEE']);
  const save = useMutation({
    mutationFn: () => (member ? members.setRoles(member.userId, roles) : members.add({ email: email.trim(), roles })),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.scoped(current?.id ?? null, 'members') });
      onClose();
    },
  });
  const toggle = (code: string) => setRoles((rs) => (rs.includes(code) ? rs.filter((r) => r !== code) : [...rs, code]));

  return (
    <Modal open onClose={onClose} title={member ? `Roles for ${member.displayName}` : 'Add member'}
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" loading={save.isPending} disabled={roles.length === 0 || (!member && !email.includes('@'))}
          onClick={() => save.mutate()}>
          {member ? 'Save roles' : 'Add member'}
        </Button>
      </>}>
      <div className="space-y-4">
        {!member && (
          <Field label="Email" hint="They need an ATLAS account already.">
            {(p) => <Input {...p} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
        )}
        <fieldset>
          <legend className="mb-2 text-[13px] font-medium">Roles</legend>
          <div className="space-y-2">
            {ROLES.map((r) => (
              <label key={r.code} className="flex cursor-pointer items-start gap-3 rounded-sm border border-border p-2.5 hover:bg-surface-2">
                <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[rgb(var(--accent))]" checked={roles.includes(r.code)} onChange={() => toggle(r.code)} />
                <span>
                  <span className="block text-[13px] font-medium">{r.label}</span>
                  <span className="block text-[12px] text-muted">{r.description}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <InlineError error={save.error} />
      </div>
    </Modal>
  );
}
