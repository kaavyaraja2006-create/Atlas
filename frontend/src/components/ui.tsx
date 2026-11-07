import { AlertTriangle, ChevronLeft, ChevronRight, Inbox, Loader2, Lock, RefreshCw, X } from 'lucide-react';
import {
  forwardRef,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
} from 'react';
import { ApiError } from '@/api/client';
import { cn } from '@/lib/format';

/* ---------------------------------------------------------------- Button */

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

const variants: Record<Variant, string> = {
  primary: 'bg-accent text-accent-fg hover:bg-accent/90 border-transparent',
  secondary: 'bg-surface text-text border-border hover:bg-surface-2',
  ghost: 'bg-transparent text-text border-transparent hover:bg-surface-2',
  danger: 'bg-danger text-white border-transparent hover:bg-danger/90',
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: 'sm' | 'md';
  loading?: boolean;
  icon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon, className, children, disabled, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-md border font-medium transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-55',
        size === 'sm' ? 'h-8 px-3 text-[13px]' : 'h-9 px-4 text-sm',
        variants[variant],
        className,
      )}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  );
});

/* ---------------------------------------------------------------- Inputs */

const control =
  'h-9 rounded-sm border border-border bg-surface px-3 text-sm text-text placeholder:text-muted ' +
  'focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 aria-[invalid=true]:border-danger';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cn(control, widthOf(className), className)} {...rest} />;
});

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, children, ...rest },
  ref,
) {
  return (
    <select ref={ref} className={cn(control, 'pr-8', widthOf(className), className)} {...rest}>
      {children}
    </select>
  );
});

/** Full width unless the caller sets a width (Tailwind cannot override w-full with w-auto by order). */
function widthOf(className?: string) {
  return /(^|\s)(w-|max-w-|min-w-)/.test(className ?? '') ? '' : 'w-full';
}

/** Label + control + hint/error, wired with ids for screen readers. */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string | undefined;
  hint?: string;
  children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string }) => ReactNode;
}) {
  const id = useId();
  const describedBy = error || hint ? `${id}-desc` : undefined;
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-[13px] font-medium text-text">
        {label}
      </label>
      {children({ id, 'aria-invalid': Boolean(error), ...(describedBy ? { 'aria-describedby': describedBy } : {}) })}
      {(error || hint) && (
        <p id={describedBy} className={cn('text-xs', error ? 'text-danger' : 'text-muted')}>
          {error ?? hint}
        </p>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------- Layout bits */

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('rounded-md border border-border bg-surface shadow-sm', className)}>{children}</div>;
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight text-text">{title}</h1>
        {description && <p className="mt-1 text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

const badgeTones = {
  neutral: 'bg-surface-2 text-muted border-border',
  info: 'bg-info/10 text-info border-info/25',
  success: 'bg-success/10 text-success border-success/25',
  warning: 'bg-warning/10 text-warning border-warning/30',
  danger: 'bg-danger/10 text-danger border-danger/25',
} as const;

export function Badge({ tone = 'neutral', children }: { tone?: keyof typeof badgeTones; children: ReactNode }) {
  return (
    <span className={cn('inline-flex items-center rounded-sm border px-1.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide', badgeTones[tone])}>
      {children}
    </span>
  );
}

const statusTone: Record<string, keyof typeof badgeTones> = {
  DRAFT: 'neutral',
  CONFIRMED: 'info',
  ISSUED: 'success',
  ACTIVE: 'success',
  CANCELLED: 'warning',
  VOID: 'danger',
  INACTIVE: 'warning',
};

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={statusTone[status] ?? 'neutral'}>{status}</Badge>;
}

/* ---------------------------------------------------------------- States */

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 py-10 text-muted justify-center">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({ title, body, action }: { title: string; body?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-12 text-center">
      <Inbox className="h-8 w-8 text-muted" aria-hidden />
      <p className="font-medium text-text">{title}</p>
      {body && <p className="max-w-sm text-muted">{body}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

/** Shows the server's message and correlation id, so a user can quote it in a bug report. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div role="alert" className="flex flex-col items-center gap-2 px-6 py-10 text-center">
      <AlertTriangle className="h-7 w-7 text-danger" aria-hidden />
      <p className="font-medium text-text">{apiError?.title ?? 'Something went wrong'}</p>
      <p className="max-w-md text-muted">{apiError?.message ?? 'The request could not be completed.'}</p>
      {apiError?.correlationId && (
        <p className="font-mono text-[11px] text-muted">Reference: {apiError.correlationId}</p>
      )}
      {onRetry && (
        <Button size="sm" onClick={onRetry} icon={<RefreshCw className="h-3.5 w-3.5" aria-hidden />}>
          Try again
        </Button>
      )}
    </div>
  );
}

/** A 403 is its own state: the user is signed in, but this view is not theirs to see. */
export function PermissionDenied({ body }: { body: string }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-2 px-6 py-12 text-center">
      <Lock className="h-7 w-7 text-muted" aria-hidden />
      <p className="font-medium text-text">You do not have access to this view</p>
      <p className="max-w-md text-muted">{body}</p>
    </div>
  );
}

/** Inline error for forms and actions; lists field errors when the server sends them. */
export function InlineError({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div role="alert" className="rounded-sm border border-danger/30 bg-danger/5 px-3 py-2 text-[13px] text-danger">
      <p>{apiError?.message ?? 'The request could not be completed.'}</p>
      {apiError && apiError.errors.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {apiError.errors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A 409 from an If-Match update: someone else saved first. Never overwrite; offer a reload. */
export function ConflictBanner({ onReload }: { onReload: () => void }) {
  return (
    <div role="alert" className="flex items-start gap-3 rounded-sm border border-warning/40 bg-warning/10 px-3 py-2.5 text-[13px]">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden />
      <div className="flex-1">
        <p className="font-medium text-text">Someone else changed this record.</p>
        <p className="text-muted">Your edit was not saved. Reload to see their version, then apply your change again.</p>
      </div>
      <Button size="sm" onClick={onReload}>
        Reload
      </Button>
    </div>
  );
}

/* ---------------------------------------------------------------- Table */

/**
 * When the table is wider than its container (narrow screens), the wrapper becomes
 * a focusable, labelled region so keyboard users can scroll it (WCAG 2.1.1).
 */
export function Table({ children, label = 'Table' }: { children: ReactNode; label?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [scrollable, setScrollable] = useState(false);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const check = () => setScrollable(el.scrollWidth > el.clientWidth);
    check();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(check);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return (
    <div
      ref={ref}
      className="overflow-x-auto focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
      {...(scrollable ? { tabIndex: 0, role: 'region', 'aria-label': `${label} (scrolls sideways)` } : {})}
    >
      <table className="w-full border-collapse text-table">{children}</table>
    </div>
  );
}

export function Th({ children, align = 'left' }: { children?: ReactNode; align?: 'left' | 'right' }) {
  return (
    <th
      scope="col"
      className={cn(
        'border-b border-border bg-surface-2 px-4 py-2 text-[11px] font-semibold uppercase tracking-wide text-muted',
        align === 'right' ? 'text-right' : 'text-left',
      )}
    >
      {children}
    </th>
  );
}

export function Td({ children, align = 'left', className }: { children?: ReactNode; align?: 'left' | 'right'; className?: string }) {
  return (
    <td className={cn('border-b border-border px-4 py-2.5 align-middle', align === 'right' && 'text-right num', className)}>
      {children}
    </td>
  );
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3 px-4 py-3 text-[13px] text-muted">
      <span>
        {totalItems} {totalItems === 1 ? 'item' : 'items'}
      </span>
      <div className="flex items-center gap-2">
        <Button size="sm" variant="ghost" disabled={page <= 0} onClick={() => onPage(page - 1)} aria-label="Previous page"
          icon={<ChevronLeft className="h-4 w-4" aria-hidden />} />
        <span className="num">
          Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
        </span>
        <Button size="sm" variant="ghost" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}
          aria-label="Next page" icon={<ChevronRight className="h-4 w-4" aria-hidden />} />
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- Dialog */

/** Native <dialog>: focus trapping, Esc to close and inert background come from the browser. */
export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  side = false,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  side?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  // An id derived from the title would contain spaces, which aria-labelledby reads
  // as a list of ids that do not exist: the dialog would have no accessible name.
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      aria-labelledby={titleId}
      className={cn(
        'border border-border bg-surface p-0 text-text shadow-xl',
        side
          ? 'm-0 ml-auto h-full max-h-full w-full max-w-md rounded-none'
          : 'w-[calc(100%-32px)] max-w-lg rounded-lg',
      )}
    >
      {open && (
        <div className="flex h-full flex-col">
          <div className="flex items-center justify-between border-b border-border px-5 py-3.5">
            <h2 id={titleId} className="text-base font-semibold">
              {title}
            </h2>
            <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close" icon={<X className="h-4 w-4" aria-hidden />} />
          </div>
          <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}

/* ---------------------------------------------------------------- Destructive confirmation */

/** For irreversible actions: the user types the record's identifier to enable the button. */
export function ConfirmAction({
  open, onClose, title, body, confirmWord, confirmLabel, onConfirm, loading, error, children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  body: string;
  confirmWord: string;
  confirmLabel: string;
  onConfirm: () => void;
  loading?: boolean;
  error?: unknown;
  children?: ReactNode;
}) {
  const [typed, setTyped] = useTypedState(open);
  return (
    <Modal open={open} onClose={onClose} title={title}
      footer={<>
        <Button onClick={onClose}>Keep it</Button>
        <Button variant="danger" disabled={typed.trim() !== confirmWord} loading={loading ?? false} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </>}>
      <div className="space-y-4">
        <p className="text-muted">{body}</p>
        {children}
        <Field label={`Type ${confirmWord} to confirm`}>
          {(p) => <Input {...p} autoComplete="off" value={typed} onChange={(e) => setTyped(e.target.value)} />}
        </Field>
        <InlineError error={error} />
      </div>
    </Modal>
  );
}

function useTypedState(open: boolean): [string, (v: string) => void] {
  const [value, setValue] = useState('');
  useEffect(() => {
    if (!open) setValue('');
  }, [open]);
  return [value, setValue];
}
