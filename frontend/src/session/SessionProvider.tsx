import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import { auth, keys, organizations } from '@/api/endpoints';
import type { Access, Me, Organization } from '@/api/types';
import { readPref, writePref } from '@/lib/storage';

const ORG_PREF = 'atlas.organization';

type Status = 'starting' | 'signed-out' | 'signed-in';

interface Session {
  status: Status;
  me: Me | null;
  organizations: Organization[];
  organizationsLoading: boolean;
  current: Organization | null;
  access: Access | null;
  can: (permission: string) => boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  selectOrganization: (id: string) => void;
  refreshOrganizations: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>('starting');
  const [currentId, setCurrentId] = useState<string | null>(() => readPref(ORG_PREF));

  const endSession = useCallback(() => {
    api.setAccessToken(null);
    api.setOrganization(null);
    queryClient.clear();
    setStatus('signed-out');
  }, [queryClient]);

  // On load, the refresh cookie (if any) silently restores the session.
  useEffect(() => {
    api.onSessionExpired = endSession;
    let cancelled = false;
    api
      .refresh()
      .catch(() => false)
      .then((ok) => {
        if (!cancelled) setStatus(ok ? 'signed-in' : 'signed-out');
      });
    return () => {
      cancelled = true;
    };
  }, [endSession]);

  const signedIn = status === 'signed-in';
  const meQuery = useQuery({ queryKey: keys.me, queryFn: auth.me, enabled: signedIn });
  const orgsQuery = useQuery({ queryKey: keys.orgs, queryFn: organizations.mine, enabled: signedIn });
  const orgList = useMemo(() => orgsQuery.data ?? [], [orgsQuery.data]);

  const current = useMemo(
    () => orgList.find((o) => o.id === currentId) ?? orgList[0] ?? null,
    [orgList, currentId],
  );

  // The client must carry the tenant header before any tenant-scoped query runs.
  api.setOrganization(signedIn ? (current?.id ?? null) : null);

  const accessQuery = useQuery({
    queryKey: keys.scoped(current?.id ?? null, 'access'),
    queryFn: organizations.access,
    enabled: signedIn && current !== null,
  });
  const access = accessQuery.data ?? null;

  const value = useMemo<Session>(
    () => ({
      status,
      me: meQuery.data ?? null,
      organizations: orgList,
      organizationsLoading: orgsQuery.isLoading,
      current,
      access,
      can: (permission) => access?.permissions.includes(permission) ?? false,
      signIn: async (email, password) => {
        await api.login(email, password);
        queryClient.clear();
        setStatus('signed-in');
      },
      signOut: async () => {
        try {
          await api.logout();
        } finally {
          endSession();
        }
      },
      selectOrganization: (id) => {
        setCurrentId(id);
        writePref(ORG_PREF, id);
      },
      refreshOrganizations: async () => {
        await queryClient.invalidateQueries({ queryKey: keys.orgs });
      },
    }),
    [status, meQuery.data, orgList, orgsQuery.isLoading, current, access, queryClient, endSession],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession must be used inside <SessionProvider>');
  return session;
}

export const Perm = {
  memberManage: 'member.manage',
  catalogueWrite: 'catalogue.write',
  customerWrite: 'customer.write',
  orderWrite: 'order.write',
  invoiceWrite: 'invoice.write',
  auditRead: 'audit.read',
  recoveryCreate: 'recovery.create',
  recoveryApprove: 'recovery.approve',
  recoveryExecute: 'recovery.execute',
  integrityRead: 'integrity.read',
  integrityManage: 'integrity.manage',
} as const;
