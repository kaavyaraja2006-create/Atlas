import { zodResolver } from '@hookform/resolvers/zod';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Search } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { keys, products } from '@/api/endpoints';
import type { Product } from '@/api/types';
import {
  Button, Card, ConflictBanner, EmptyState, ErrorState, Field, InlineError, Input, Modal, PageHeader,
  Pagination, Select, Spinner, StatusBadge, Table, Td, Th,
} from '@/components/ui';
import { HistoryLink } from '@/components/HistoryLink';
import { dateTime, money } from '@/lib/format';
import { Perm, useSession } from '@/session/SessionProvider';

const amount = z
  .string()
  .trim()
  .regex(/^\d{1,15}(\.\d{1,4})?$/, 'Up to 15 digits and 4 decimals, not negative');

const createSchema = z.object({
  sku: z.string().trim().min(1, 'Enter a SKU').max(64).regex(/^[A-Za-z0-9._-]+$/, "Letters, digits, '.', '_' or '-'"),
  name: z.string().trim().min(1, 'Enter a name').max(200),
  unitPrice: amount,
  unitCost: amount,
});
type CreateValues = z.infer<typeof createSchema>;

const editSchema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(200),
  unitPrice: amount,
  unitCost: amount,
  active: z.boolean(),
  reason: z.string().trim().max(500),
});
type EditValues = z.infer<typeof editSchema>;

export function ProductsPage() {
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const currency = current?.currency ?? 'INR';
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [active, setActive] = useState<'' | 'true' | 'false'>('');
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const params = { page, size: 20, q: search || undefined, active: active === '' ? undefined : active === 'true' };
  const list = useQuery({
    queryKey: keys.scoped(orgId, 'products', params),
    queryFn: () => products.list(params),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      <PageHeader
        title="Catalogue"
        description="Products and their current prices. Every price change is recorded with its previous value."
        actions={can(Perm.catalogueWrite) && (
          <Button variant="primary" icon={<Plus className="h-4 w-4" aria-hidden />} onClick={() => setCreating(true)}>
            New product
          </Button>
        )}
      />
      <Card>
        <div className="flex flex-wrap items-center gap-2 border-b border-border p-3">
          <div className="relative min-w-[12rem] flex-1">
            <Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-muted" aria-hidden />
            <Input aria-label="Search products" placeholder="Search by SKU or name" className="pl-9" value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
          </div>
          <Select aria-label="Filter by status" className="w-auto" value={active}
            onChange={(e) => { setActive(e.target.value as typeof active); setPage(0); }}>
            <option value="">All products</option>
            <option value="true">Active</option>
            <option value="false">Inactive</option>
          </Select>
        </div>
        {list.isLoading ? <Spinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> :
          list.data && list.data.items.length === 0 ? (
            <EmptyState title={search || active ? 'No matching products' : 'No products yet'}
              body={search || active ? 'Try a different search or filter.' : 'Products you add appear here.'} />
          ) : list.data && (
            <>
              <Table>
                <thead>
                  <tr><Th>SKU</Th><Th>Name</Th><Th align="right">Price</Th><Th align="right">Cost</Th><Th>Status</Th><Th>Updated</Th></tr>
                </thead>
                <tbody>
                  {list.data.items.map((p) => (
                    <tr key={p.id} className="cursor-pointer hover:bg-surface-2" onClick={() => setEditingId(p.id)}>
                      <Td className="font-mono text-[12px]">
                        <button type="button" className="text-left hover:underline" onClick={(e) => { e.stopPropagation(); setEditingId(p.id); }}>
                          {p.sku}
                        </button>
                      </Td>
                      <Td>{p.name}</Td>
                      <Td align="right">{money(p.unitPrice, currency)}</Td>
                      <Td align="right" className="text-muted">{money(p.unitCost, currency)}</Td>
                      <Td><StatusBadge status={p.active ? 'ACTIVE' : 'INACTIVE'} /></Td>
                      <Td className="whitespace-nowrap text-muted">{dateTime(p.updatedAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={page} totalPages={list.data.page.totalPages} totalItems={list.data.page.totalItems} onPage={setPage} />
            </>
          )}
      </Card>
      <CreateProductModal open={creating} onClose={() => setCreating(false)} />
      {editingId && <ProductEditor productId={editingId} onClose={() => setEditingId(null)} />}
    </>
  );
}

function CreateProductModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { current } = useSession();
  const queryClient = useQueryClient();
  const { register, handleSubmit, formState, reset } = useForm<CreateValues>({ resolver: zodResolver(createSchema) });
  const create = useMutation({
    mutationFn: (v: CreateValues) => products.create({ ...v, active: true }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.scoped(current?.id ?? null, 'products') });
      reset();
      onClose();
    },
  });
  const close = () => { create.reset(); onClose(); };
  return (
    <Modal open={open} onClose={close} title="New product"
      footer={<>
        <Button onClick={close}>Cancel</Button>
        <Button variant="primary" type="submit" form="create-product" loading={create.isPending}>Create product</Button>
      </>}>
      <form id="create-product" noValidate onSubmit={handleSubmit((v) => create.mutate(v))} className="grid gap-4 sm:grid-cols-2">
        <Field label="SKU" error={formState.errors.sku?.message}>{(p) => <Input {...p} {...register('sku')} />}</Field>
        <Field label="Name" error={formState.errors.name?.message}>{(p) => <Input {...p} {...register('name')} />}</Field>
        <Field label="Unit price" error={formState.errors.unitPrice?.message}>
          {(p) => <Input {...p} inputMode="decimal" {...register('unitPrice')} />}
        </Field>
        <Field label="Unit cost" error={formState.errors.unitCost?.message}>
          {(p) => <Input {...p} inputMode="decimal" {...register('unitCost')} />}
        </Field>
        <div className="sm:col-span-2"><InlineError error={create.error} /></div>
      </form>
    </Modal>
  );
}

/**
 * Edits send If-Match with the version that was loaded. If someone else saved
 * in between, the server answers 409 and this shows a ConflictBanner instead
 * of overwriting their change.
 */
export function ProductEditor({ productId, onClose }: { productId: string; onClose: () => void }) {
  const { current, can } = useSession();
  const orgId = current?.id ?? null;
  const queryClient = useQueryClient();
  const product = useQuery({ queryKey: keys.scoped(orgId, 'product', productId), queryFn: () => products.get(productId) });

  return (
    <Modal open onClose={onClose} title={product.data ? `${product.data.sku} · ${product.data.name}` : 'Product'} side>
      {product.isLoading ? <Spinner /> : product.isError ? <ErrorState error={product.error} /> : product.data && (
        <ProductForm
          key={product.data.version}
          product={product.data}
          currency={current?.currency ?? 'INR'}
          readOnly={!can(Perm.catalogueWrite)}
          onReload={() => void product.refetch()}
          onSaved={async (saved) => {
            queryClient.setQueryData(keys.scoped(orgId, 'product', productId), saved);
            await queryClient.invalidateQueries({ queryKey: keys.scoped(orgId, 'products') });
            onClose();
          }}
        />
      )}
    </Modal>
  );
}

function ProductForm({ product, currency, readOnly, onReload, onSaved }: {
  product: Product; currency: string; readOnly: boolean; onReload: () => void; onSaved: (p: Product) => Promise<void>;
}) {
  const { register, handleSubmit, formState } = useForm<EditValues>({
    resolver: zodResolver(editSchema),
    defaultValues: {
      name: product.name,
      unitPrice: String(product.unitPrice),
      unitCost: String(product.unitCost),
      active: product.active,
      reason: '',
    },
  });
  const save = useMutation({
    mutationFn: (v: EditValues) =>
      products.update(product.id, product.version, {
        name: v.name, unitPrice: v.unitPrice, unitCost: v.unitCost, active: v.active,
        ...(v.reason ? { reason: v.reason } : {}),
      }),
    onSuccess: onSaved,
  });
  const conflict = save.error instanceof ApiError && save.error.isConflict;

  return (
    <form noValidate onSubmit={handleSubmit((v) => save.mutate(v))} className="space-y-4">
      <dl className="grid grid-cols-2 gap-3 rounded-sm bg-surface-2 p-3 text-[13px]">
        <div><dt className="text-muted">Current price</dt><dd className="num font-medium">{money(product.unitPrice, currency)}</dd></div>
        <div><dt className="text-muted">Version</dt><dd className="num font-medium">v{product.version}</dd></div>
      </dl>
      <div className="-mt-2 flex justify-end"><HistoryLink entityType="product" entityId={product.id} version={product.version} /></div>
      <fieldset disabled={readOnly} className="space-y-4">
        <Field label="Name" error={formState.errors.name?.message}>{(p) => <Input {...p} {...register('name')} />}</Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Unit price" error={formState.errors.unitPrice?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...register('unitPrice')} />}
          </Field>
          <Field label="Unit cost" error={formState.errors.unitCost?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...register('unitCost')} />}
          </Field>
        </div>
        <label className="flex items-center gap-2 text-[13px]">
          <input type="checkbox" className="h-4 w-4 accent-[rgb(var(--accent))]" {...register('active')} />
          Active (can be added to orders)
        </label>
        <Field label="Reason for change" error={formState.errors.reason?.message} hint="Stored in the audit history with the change.">
          {(p) => <Input {...p} placeholder="e.g. supplier price increase" {...register('reason')} />}
        </Field>
      </fieldset>
      {conflict ? <ConflictBanner onReload={onReload} /> : <InlineError error={save.error} />}
      {!readOnly && (
        <div className="flex justify-end">
          <Button type="submit" variant="primary" loading={save.isPending} disabled={conflict}>Save changes</Button>
        </div>
      )}
      {readOnly && <p className="text-[13px] text-muted">You can view this product but not change it.</p>}
    </form>
  );
}
