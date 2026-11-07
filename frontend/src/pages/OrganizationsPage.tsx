import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { Building2, ChevronRight } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { organizations } from '@/api/endpoints';
import { Badge, Button, Card, Field, InlineError, Input, Spinner } from '@/components/ui';
import { SLUG_PATTERN, slugify } from '@/lib/format';
import { useSession } from '@/session/SessionProvider';

const schema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(200),
  slug: z.string().regex(SLUG_PATTERN, '3–64 lowercase letters, digits or hyphens'),
});
type Values = z.infer<typeof schema>;

export function OrganizationsPage() {
  const session = useSession();
  const navigate = useNavigate();
  const { register, handleSubmit, setValue, formState } = useForm<Values>({ resolver: zodResolver(schema) });
  const nameField = register('name');

  const create = useMutation({
    mutationFn: organizations.create,
    onSuccess: async (org) => {
      await session.refreshOrganizations();
      session.selectOrganization(org.id);
      navigate('/products');
    },
  });

  const open = (id: string) => {
    session.selectOrganization(id);
    navigate('/products');
  };

  return (
    <div className="mx-auto max-w-2xl px-4 py-12">
      <h1 className="text-xl font-semibold tracking-tight">Organizations</h1>
      <p className="mt-1 text-muted">Choose where to work, or create a new organization.</p>

      <Card className="mt-6">
        {session.organizationsLoading ? (
          <Spinner />
        ) : session.organizations.length === 0 ? (
          <p className="px-5 py-6 text-muted">You are not a member of any organization yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {session.organizations.map((org) => (
              <li key={org.id}>
                <button type="button" onClick={() => open(org.id)}
                  className="flex w-full items-center gap-3 px-5 py-3.5 text-left hover:bg-surface-2">
                  <Building2 className="h-5 w-5 text-muted" aria-hidden />
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium">{org.name}</span>
                    <span className="block text-[12px] text-muted">{org.slug} · {org.currency}</span>
                  </span>
                  <span className="hidden gap-1 sm:flex">
                    {org.myRoles.map((r) => <Badge key={r}>{r.replace(/_/g, ' ')}</Badge>)}
                  </span>
                  <ChevronRight className="h-4 w-4 text-muted" aria-hidden />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card className="mt-6 p-5">
        <h2 className="font-semibold">New organization</h2>
        <p className="mt-1 text-[13px] text-muted">You will be its first organization admin.</p>
        <form noValidate onSubmit={handleSubmit((v) => create.mutate(v))} className="mt-4 grid gap-4 sm:grid-cols-2">
          <Field label="Name" error={formState.errors.name?.message}>
            {(p) => (
              <Input {...p} {...nameField} onChange={(e) => {
                void nameField.onChange(e);
                setValue('slug', slugify(e.target.value), { shouldValidate: formState.isSubmitted });
              }} />
            )}
          </Field>
          <Field label="Slug" error={formState.errors.slug?.message} hint="Used in links; cannot be changed later.">
            {(p) => <Input {...p} {...register('slug')} />}
          </Field>
          <div className="sm:col-span-2">
            <InlineError error={create.error} />
          </div>
          <div className="sm:col-span-2 flex justify-end">
            <Button type="submit" variant="primary" loading={create.isPending}>Create organization</Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
