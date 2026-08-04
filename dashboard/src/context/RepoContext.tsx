import { createContext, useContext, useState, useEffect, type ReactNode } from 'react';

const uuidv4 = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
  const r = Math.random() * 16 | 0;
  return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
});

export interface Repo {
  id: string;
  fullName: string;   // "owner/repo"
  url: string;        // "https://github.com/owner/repo"
  addedAt: string;
}

interface RepoContextValue {
  repos: Repo[];
  activeRepo: Repo | null;
  addRepo: (url: string) => Repo | null;
  removeRepo: (id: string) => void;
  setActiveRepo: (id: string) => void;
}

const STORAGE_KEY = 'gitoracle:repos';
const ACTIVE_KEY  = 'gitoracle:activeRepoId';

function parseGitHubUrl(input: string): { fullName: string; url: string } | null {
  const trimmed = input.trim().replace(/\.git$/, '');
  // Accept "https://github.com/owner/repo" or "owner/repo"
  const httpsMatch = trimmed.match(/github\.com\/([^/]+\/[^/]+)$/);
  if (httpsMatch) return { fullName: httpsMatch[1], url: `https://github.com/${httpsMatch[1]}` };
  const shortMatch = trimmed.match(/^([a-zA-Z0-9_.-]+\/[a-zA-Z0-9_.-]+)$/);
  if (shortMatch) return { fullName: shortMatch[1], url: `https://github.com/${shortMatch[1]}` };
  return null;
}

const RepoContext = createContext<RepoContextValue>({
  repos: [], activeRepo: null,
  addRepo: () => null, removeRepo: () => {}, setActiveRepo: () => {}
});

export function RepoProvider({ children }: { children: ReactNode }) {
  const [repos, setRepos] = useState<Repo[]>(() => {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); } catch { return []; }
  });
  const [activeId, setActiveId] = useState<string | null>(() =>
    localStorage.getItem(ACTIVE_KEY)
  );

  useEffect(() => { localStorage.setItem(STORAGE_KEY, JSON.stringify(repos)); }, [repos]);
  useEffect(() => { if (activeId) localStorage.setItem(ACTIVE_KEY, activeId); }, [activeId]);

  const activeRepo = repos.find(r => r.id === activeId) ?? repos[0] ?? null;

  const addRepo = (input: string): Repo | null => {
    const parsed = parseGitHubUrl(input);
    if (!parsed) return null;
    if (repos.some(r => r.fullName === parsed.fullName)) return repos.find(r => r.fullName === parsed.fullName)!;
    const repo: Repo = { id: uuidv4(), ...parsed, addedAt: new Date().toISOString() };
    setRepos(prev => [...prev, repo]);
    if (!activeId) setActiveId(repo.id);
    return repo;
  };

  const removeRepo = (id: string) => {
    setRepos(prev => prev.filter(r => r.id !== id));
    if (activeId === id) setActiveId(repos.find(r => r.id !== id)?.id ?? null);
  };

  const setActiveRepo = (id: string) => setActiveId(id);

  return (
    <RepoContext.Provider value={{ repos, activeRepo, addRepo, removeRepo, setActiveRepo }}>
      {children}
    </RepoContext.Provider>
  );
}

export const useRepos = () => useContext(RepoContext);
