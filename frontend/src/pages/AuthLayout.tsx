import type { ReactNode } from 'react';

/** Shared frame for sign-in and registration. */
export function AuthLayout({ title, subtitle, children, footer }: {
  title: string; subtitle: string; children: ReactNode; footer: ReactNode;
}) {
  return (
    <div className="grid min-h-full lg:grid-cols-[1fr_1.1fr]">
      <section className="hidden flex-col justify-between bg-sidebar p-10 text-sidebar-text lg:flex">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-accent font-bold text-white">A</div>
          <span className="text-base font-semibold tracking-wide text-white">ATLAS</span>
        </div>
        <div className="max-w-md">
          <p className="text-2xl font-semibold leading-snug text-white">
            Undo a bad change without undoing everyone's work.
          </p>
          <p className="mt-3 leading-relaxed">
            Every price, order and invoice keeps a tamper-evident history and records exactly which
            values it was computed from.
          </p>
        </div>
        <p className="text-[12px]">Audit-Tracked Lineage and Adaptive Selective Recovery</p>
      </section>
      <section className="flex items-center justify-center px-4 py-10">
        <div className="w-full max-w-sm">
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          <p className="mt-1 text-muted">{subtitle}</p>
          <div className="mt-6">{children}</div>
          <div className="mt-6 text-[13px] text-muted">{footer}</div>
        </div>
      </section>
    </div>
  );
}
