import { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import {
  getApiKey,
  setApiKey as setStoredApiKey,
  clearApiKey,
  subscribe,
  purgeLegacyPersistedKey,
} from '../auth/apiKeyStore';

interface AuthContextType {
  apiKey: string | null;
  login: (key: string) => void;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * The key lives in the in-memory store, not in localStorage — see
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

  useEffect(() => subscribe(setApiKeyState), []);

  const login = (key: string) => setStoredApiKey(key);
  const logout = () => clearApiKey();

  return (
    <AuthContext.Provider value={{ apiKey, login, logout, isAuthenticated: !!apiKey }}>
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
