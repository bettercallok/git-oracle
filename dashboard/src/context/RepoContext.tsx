import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { apiClient } from '../api/client';
import { useAuth } from './AuthContext';

export interface Repo {
  id: string;         // "owner/repo"
  fullName: string;   // "owner/repo"
  url: string;        // "https://github.com/owner/repo"
}

interface RepoContextValue {
  repos: Repo[];
  activeRepo: Repo | null;
  setActiveRepo: (id: string) => void;
  isLoading: boolean;
  loadFailed: boolean;
  refresh: () => void;
}

const ACTIVE_KEY = 'gitoracle:activeRepoId';
/** The list used to live in this browser, where it had no effect on what jobs could use. */
const OLD_LIST_KEY = 'gitoracle:repos';

const RepoContext = createContext<RepoContextValue>({
  repos: [], activeRepo: null, setActiveRepo: () => {}, isLoading: false, loadFailed: false, refresh: () => {},
});

const readActive = (): string | null => {
  try {
    localStorage.removeItem(OLD_LIST_KEY);
    return localStorage.getItem(ACTIVE_KEY);
  } catch {
    return null;
  }
};

/**
 * The repositories are the organisation's real allow-list from the server. Only
 * which one is "active", a convenience for pre-filling forms, stays in the browser.
 */
export function RepoProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  const [repos, setRepos] = useState<Repo[]>([]);
  const [activeId, setActiveId] = useState<string | null>(readActive);
  const [isLoading, setIsLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const refresh = useCallback(() => {
    setIsLoading(true);
    apiClient.get('/repos')
      .then(({ data }) => {
        setRepos((data.repos as string[]).map(fullName => ({ id: fullName, fullName, url: `https://github.com/${fullName}` })));
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true))
      .finally(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    if (isAuthenticated) refresh();
  }, [isAuthenticated, refresh]);

  const setActiveRepo = (id: string) => {
    setActiveId(id);
    try {
      localStorage.setItem(ACTIVE_KEY, id);
    } catch {
      // Storage can be unavailable. It only means the choice isn't remembered.
    }
  };

  const activeRepo = repos.find(r => r.id === activeId) ?? repos[0] ?? null;

  return (
    <RepoContext.Provider value={{ repos, activeRepo, setActiveRepo, isLoading, loadFailed, refresh }}>
      {children}
    </RepoContext.Provider>
  );
}

export const useRepos = () => useContext(RepoContext);
