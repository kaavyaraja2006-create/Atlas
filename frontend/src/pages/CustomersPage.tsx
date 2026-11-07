import { zodResolver } from '@hookform/resolvers/zod';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Search } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { customers, keys } from '@/api/endpoints';
import {
  Button, Card, EmptyState, ErrorState, Field, InlineError, Input, Modal, PageHeader, Pagination, Spinner, Table, Td, Th,
} from '@/components/ui';
import { dateTime } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

const schema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(200),
  email: z.union([z.literal(''), z.string().trim().email('Enter a valid email').max(320)]),
});
type Values = z.infer<typeof schema>;

export function CustomersPage() {
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const params = { page, size: 20, q: search || undefined };
  const list = useQuery({
    queryKey: keys.scoped(orgId, 'customers', params),
    queryFn: () => customers.list(params),
    placeholderData: keepPreviousData,
  });

  const { register, handleSubmit, formState, reset } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '' } });
  const create = useMutation({
    mutationFn: (v: Values) => customers.create({ name: v.name, ...(v.email ? { email: v.email } : {}) }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.scoped(orgId, 'customers') });
      reset();
      setCreating(false);
    },
  });

  return (
    <>
      <PageHeader title="Customers" description="Who you sell to."
        actions={can(Perm.customerWrite) && (
          <Button variant="primary" icon={<Plus className="h-4 w-4" aria-hidden />} onClick={() => setCreating(true)}>New customer</Button>
        )} />
      <Card>
        <div className="border-b border-border p-3">
          <div className="relative max-w-md">
            <Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-muted" aria-hidden />
            <Input aria-label="Search customers" placeholder="Search by name or email" className="pl-9" value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
          </div>
        </div>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? (
            <EmptyState title={search ? 'No matching customers' : 'No customers yet'} />
          ) : list.data && (
            <>
              <Table>
                <thead><tr><Th>Name</Th><Th>Email</Th><Th>Added</Th></tr></thead>
                <tbody>
                  {list.data.items.map((c) => (
                    <tr key={c.id}>
                      <Td className="font-medium">{c.name}</Td>
                      <Td className="text-muted">{c.email ?? '—'}</Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(c.createdAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
      <Modal open={creating} onClose={() => { create.reset(); setCreating(false); }} title="New customer"
        footer={<>
          <Button onClick={() => setCreating(false)}>Cancel</Button>
          <Button variant="primary" type="submit" form="create-customer" loading={create.isPending}>Create customer</Button>
        </>}>
        <form id="create-customer" noValidate onSubmit={handleSubmit((v) => create.mutate(v))} className="space-y-4">
          <Field label="Name" error={formState.errors.name?.message}>{(p) => <Input {...p} {...register('name')} />}</Field>
          <Field label="Email (optional)" error={formState.errors.email?.message}>
            {(p) => <Input {...p} type="email" {...register('email')} />}
          </Field>
          <InlineError error={create.error} />
        </form>
      </Modal>
    </>
  );
}
