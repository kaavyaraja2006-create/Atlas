import { Boxes, FileText, History, LifeBuoy, LogOut, Menu, Moon, ShoppingCart, Sun, Users, UserRound, X, ShieldCheck, Database } from 'lucide-react';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Select } from '@/components/ui';
import { cn } from '@/lib/format';
import { readPref, writePref } from '@/lib/storage';
import { Perm, useSession } from '@/session/SessionProvider';

const nav = [
  { to: '/products', label: 'Catalogue', icon: Boxes },
  { to: '/customers', label: 'Customers', icon: UserRound },
  { to: '/orders', label: 'Orders', icon: ShoppingCart },
  { to: '/invoices', label: 'Invoices', icon: FileText },
  { to: '/audit', label: 'Audit', icon: History, permission: Perm.auditRead },
  { to: '/recovery', label: 'Recovery', icon: LifeBuoy, permission: Perm.auditRead },
  { to: '/integrity', label: 'Integrity', icon: ShieldCheck, permission: Perm.integrityRead },
  { to: '/members', label: 'Members', icon: Users, permission: Perm.memberManage },
  { to: '/graph/schema', label: 'Schema', icon: Database, platformAdmin: true },
];

function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'));
  const toggle = () => {
    const next = !dark;
    document.documentElement.classList.toggle('dark', next);
    writePref('atlas.theme', next ? 'dark' : 'light');
    setDark(next);
  };
  return { dark, toggle, saved: readPref('atlas.theme') };
}

export function AppShell() {
  const { me, organizations, current, selectOrganization, signOut, can } = useSession();
  const navigate = useNavigate();
  const theme = useTheme();
  const [menuOpen, setMenuOpen] = useState(false);
  const items = nav.filter((item) => ('platformAdmin' in item ? !!me?.platformAdmin : !item.permission || can(item.permission)));

  const sidebar = (
    <nav aria-label="Main" className="flex h-full flex-col bg-sidebar text-sidebar-text">
      <div className="flex h-14 items-center gap-2 px-5">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-accent text-[13px] font-bold text-white">A</div>
        <span className="text-[15px] font-semibold tracking-wide text-white">ATLAS</span>
      </div>
      <ul className="mt-2 flex-1 space-y-0.5 px-3">
        {items.map(({ to, label, icon: Icon }) => (
          <li key={to}>
            <NavLink
              to={to}
              onClick={() => setMenuOpen(false)}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-md px-3 py-2 text-[13px] font-medium transition-colors',
                  isActive ? 'bg-white/10 text-sidebar-active' : 'hover:bg-white/5 hover:text-white',
                )
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
      <div className="border-t border-white/10 px-5 py-4 text-[12px]">
        <p className="truncate font-medium text-white">{me?.displayName ?? ''}</p>
        <p className="truncate">{me?.email ?? ''}</p>
      </div>
    </nav>
  );

  return (
    <div className="flex min-h-full">
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 lg:block">{sidebar}</aside>
      {menuOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-black/40" onClick={() => setMenuOpen(false)} aria-hidden />
          <aside className="relative h-full w-64">{sidebar}</aside>
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-border bg-surface/95 px-4 backdrop-blur sm:px-6">
          <button
            type="button"
            className="rounded-md p-1.5 text-muted hover:bg-surface-2 lg:hidden"
            onClick={() => setMenuOpen((v) => !v)}
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
          >
            {menuOpen ? <X className="h-5 w-5" aria-hidden /> : <Menu className="h-5 w-5" aria-hidden />}
          </button>
          <label className="sr-only" htmlFor="org-switcher">Organization</label>
          <Select
            id="org-switcher"
            className="h-8 w-auto max-w-[14rem] text-[13px]"
            value={current?.id ?? ''}
            onChange={(e) => {
              if (e.target.value === '__new') navigate('/organizations');
              else {
                selectOrganization(e.target.value);
                navigate('/products');
              }
            }}
          >
            {organizations.map((o) => (
              <option key={o.id} value={o.id}>{o.name}</option>
            ))}
            <option value="__new">+ New organization…</option>
          </Select>
          <div className="ml-auto flex items-center gap-1">
            <button
              type="button"
              onClick={theme.toggle}
              className="rounded-md p-2 text-muted hover:bg-surface-2 hover:text-text"
              aria-label={theme.dark ? 'Switch to light theme' : 'Switch to dark theme'}
            >
              {theme.dark ? <Sun className="h-4 w-4" aria-hidden /> : <Moon className="h-4 w-4" aria-hidden />}
            </button>
            <button
              type="button"
              onClick={() => void signOut().then(() => navigate('/sign-in'))}
              aria-label="Sign out"
              className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-[13px] text-muted hover:bg-surface-2 hover:text-text"
            >
              <LogOut className="h-4 w-4" aria-hidden />
              <span className="hidden sm:inline">Sign out</span>
            </button>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
