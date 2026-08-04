import { createContext, useContext, useState, useEffect, ReactNode } from 'react';

interface AuthContextType {
  apiKey: string | null;
  login: (key: string) => void;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [apiKey, setApiKey] = useState<string | null>(() => {
    return localStorage.getItem('gitoracle_api_key');
  });

  const login = (key: string) => {
    localStorage.setItem('gitoracle_api_key', key);
    setApiKey(key);
  };

  const logout = () => {
    localStorage.removeItem('gitoracle_api_key');
    setApiKey(null);
  };

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
