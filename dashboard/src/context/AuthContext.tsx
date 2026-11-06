import { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import {
  getApiKey,
  setApiKey as setStoredApiKey,
  clearApiKey,
  subscribe,
  purgeLegacyPersistedKey,
} from '../auth/apiKeyStore';
import { apiClient } from '../api/client';

export type OrgRole = 'VIEWER' | 'MEMBER' | 'ADMIN' | 'OWNER';

/** Lowest first. */
export const ROLES: OrgRole[] = ['VIEWER', 'MEMBER', 'ADMIN', 'OWNER'];

export const ROLE_LABEL: Record<OrgRole, string> = { VIEWER: 'Viewer', MEMBER: 'Member', ADMIN: 'Admin', OWNER: 'Owner' };

export interface Organization {
  id: string;
  name: string | null;
  role: OrgRole;
  active: boolean;
}

export interface SignedInUser {
  id: string;
  login: string;
  admin: boolean;
}

interface AuthContextType {
  apiKey: string | null;
  /** Set when signed in with GitHub. */
  user: SignedInUser | null;
  /** True until the first /auth/me answers. */
  isLoading: boolean;
  login: (key: string) => void;
  logout: () => void;
  isAuthenticated: boolean;
  /** Runs the whole system. Separate from any role in an organisation. */
  isPlatformAdmin: boolean;
  organizations: Organization[];
  activeOrganization: Organization | null;
  /** The person's role in the active organisation; null for an API key. */
  role: OrgRole | null;
  /** Hides what the server would refuse. The server still decides. */
  canWrite: boolean;
  canManageOrg: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * Two ways in. Sign in with GitHub sets an httpOnly cookie this code can't read,
 * so the only way to learn about it is to ask the server (/auth/me). The API key
 * form, shown only without GitHub sign-in, keeps its key in an in-memory store, not
 * in localStorage: see src/auth/apiKeyStore.ts for why.
 *
 * React state here mirrors the store so components re-render on login/logout;
 * the store remains the single source of truth, because the axios client needs
 * to read the key from outside the React tree.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [apiKey, setApiKeyState] = useState<string | null>(() => {
    // Drop any key a previous build left on disk. Without this the old value
    // would remain readable by the same XSS this change exists to limit.
    purgeLegacyPersistedKey();
    return getApiKey();
  });
  const [user, setUser] = useState<SignedInUser | null>(null);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [keyIsAdmin, setKeyIsAdmin] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => subscribe(setApiKeyState), []);

  // Again when a key is entered, to learn what that key may do.
  useEffect(() => {
    apiClient.get('/auth/me')
      .then(({ data }) => {
        if (data.kind === 'user') {
          setUser({ id: data.id, login: data.login, admin: data.admin });
          setOrganizations(data.organizations ?? []);
        } else {
          setKeyIsAdmin(!!data.admin);
        }
      })
      .catch(() => { /* Not signed in, or an older gateway: the login page handles both. */ })
      .finally(() => setIsLoading(false));
  }, [apiKey]);

  const login = (key: string) => setStoredApiKey(key);

  const logout = () => {
    clearApiKey();
    if (!user) return;
    // A full page load, so nothing fetched for this user stays in memory.
    apiClient.post('/auth/logout')
      .catch(() => { /* The cookie may remain; the session still expires on its own. */ })
      .finally(() => { window.location.href = '/login'; });
  };

  const activeOrganization = organizations.find(organization => organization.active) ?? null;
  const role = activeOrganization?.role ?? null;
  const isPlatformAdmin = user ? user.admin : keyIsAdmin;

  return (
    <AuthContext.Provider value={{
      apiKey,
      user,
      isLoading,
      login,
      logout,
      isAuthenticated: !!apiKey || !!user,
      isPlatformAdmin,
      organizations,
      activeOrganization,
      role,
      canWrite: isPlatformAdmin || !user || role !== 'VIEWER',
      canManageOrg: isPlatformAdmin || role === 'ADMIN' || role === 'OWNER',
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
