import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Spinner } from '@/components/ui';
import { IncidentPage } from './pages/IncidentPage';
import { RecoveryPage } from './pages/RecoveryPage';
import { AuditPage } from '@/pages/AuditPage';
import { CustomersPage } from '@/pages/CustomersPage';
import { IntegrityPage } from '@/pages/IntegrityPage';
import { SchemaPage } from '@/pages/SchemaPage';
import { HistoryPage } from '@/pages/HistoryPage';
import { InvoiceDetailPage } from '@/pages/InvoiceDetailPage';
import { InvoicesPage } from '@/pages/InvoicesPage';
import { MembersPage } from '@/pages/MembersPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { OrderDetailPage } from '@/pages/OrderDetailPage';
import { OrdersPage } from '@/pages/OrdersPage';
import { OrganizationsPage } from '@/pages/OrganizationsPage';
import { ProductsPage } from '@/pages/ProductsPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { SignInPage } from '@/pages/SignInPage';
import { useSession } from '@/session/SessionProvider';

function FullPage({ children }: { children: ReactNode }) {
  return <div className="flex min-h-full items-center justify-center bg-bg">{children}</div>;
}

function RequireSignedIn({ children }: { children: ReactNode }) {
  const { status } = useSession();
  const location = useLocation();
  if (status === 'starting') return <FullPage><Spinner label="Restoring your session…" /></FullPage>;
  if (status === 'signed-out') {
    return <Navigate to={`/sign-in?next=${encodeURIComponent(location.pathname)}`} replace />;
  }
  return <>{children}</>;
}

/** Tenant pages need an organization; a new user is sent to create one. */
function RequireOrganization({ children }: { children: ReactNode }) {
  const { current, organizationsLoading } = useSession();
  if (organizationsLoading) return <FullPage><Spinner /></FullPage>;
  if (!current) return <Navigate to="/organizations" replace />;
  return <>{children}</>;
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { status } = useSession();
  if (status === 'starting') return <FullPage><Spinner /></FullPage>;
  if (status === 'signed-in') return <Navigate to="/" replace />;
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/sign-in" element={<PublicOnly><SignInPage /></PublicOnly>} />
      <Route path="/register" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      <Route path="/organizations" element={<RequireSignedIn><OrganizationsPage /></RequireSignedIn>} />
      <Route
        element={
          <RequireSignedIn>
            <RequireOrganization>
              <AppShell />
            </RequireOrganization>
          </RequireSignedIn>
        }
      >
        <Route index element={<Navigate to="/products" replace />} />
        <Route path="/products" element={<ProductsPage />} />
        <Route path="/customers" element={<CustomersPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/:id" element={<OrderDetailPage />} />
        <Route path="/invoices" element={<InvoicesPage />} />
        <Route path="/invoices/:id" element={<InvoiceDetailPage />} />
        <Route path="/members" element={<MembersPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="/recovery" element={<RecoveryPage />} />
        <Route path="/recovery/:id" element={<IncidentPage />} />
        <Route path="/integrity" element={<IntegrityPage />} />
        <Route path="/graph/schema" element={<SchemaPage />} />
        <Route path="/history/:entityType/:entityId" element={<HistoryPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
