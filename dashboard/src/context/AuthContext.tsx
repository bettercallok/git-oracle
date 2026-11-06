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

export interface SignedInUser {
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
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * Two ways in. Sign in with GitHub sets an httpOnly cookie this code can't read,
 * so the only way to learn about it is to ask the server (/auth/me). The API key
 * fallback lives in the in-memory store, not in localStorage — see
 * src/auth/apiKeyStore.ts for why, and for what this does and does not buy.
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
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => subscribe(setApiKeyState), []);

  useEffect(() => {
    apiClient.get('/auth/me')
      .then(({ data }) => {
        if (data.kind === 'user') setUser({ login: data.login, admin: data.admin });
      })
      .catch(() => { /* Not signed in, or an older gateway: the login page handles both. */ })
      .finally(() => setIsLoading(false));
  }, []);

  const login = (key: string) => setStoredApiKey(key);

  const logout = () => {
    clearApiKey();
    if (!user) return;
    // A full page load, so nothing fetched for this user stays in memory.
    apiClient.post('/auth/logout')
      .catch(() => { /* The cookie may remain; the session still expires on its own. */ })
      .finally(() => { window.location.href = '/login'; });
  };

  return (
    <AuthContext.Provider value={{ apiKey, user, isLoading, login, logout, isAuthenticated: !!apiKey || !!user }}>
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
