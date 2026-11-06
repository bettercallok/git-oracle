import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, FolderGit2, GitBranch } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useRepos } from '../context/RepoContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

const errorMessage = (error: unknown): string => {
  const data = (error as { response?: { data?: { error?: string; message?: string; detail?: string } } }).response?.data;
  return data?.error && data.error !== 'Forbidden' ? data.error : data?.detail ?? data?.message ?? 'Something went wrong. Please try again.';
};

interface GitHubConnection {
  connected: boolean;
  canConnect: boolean;
  shared: boolean;
}

interface InstallationRepo {
  fullName: string;
  allowed: boolean;
}

const CONNECT_OUTCOME: Record<string, { tone: 'success' | 'danger' | 'info'; text: string }> = {
  connected: { tone: 'success', text: 'GitHub is connected. Choose the repositories this organisation may use.' },
  failed: { tone: 'danger', text: "Connecting GitHub didn't finish. Please try again." },
  wrong_user: { tone: 'danger', text: 'That was a different GitHub account from the one that started connecting.' },
  not_yours: { tone: 'danger', text: "Your GitHub account doesn't have access to that installation." },
  taken: { tone: 'danger', text: 'Another organisation already uses that GitHub installation.' },
  requested: { tone: 'info', text: 'GitHub sent your request to install the App to an owner of that account. Connect again once they approve it.' },
};

export default function RepoManager() {
  const { isPlatformAdmin, activeOrganization, canManageOrg, role } = useAuth();
  const { repos, activeRepo, setActiveRepo, isLoading, loadFailed, refresh } = useRepos();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const [input, setInput] = useState('');
  const outcome = CONNECT_OUTCOME[searchParams.get('github') ?? ''];
  const isOwner = isPlatformAdmin || role === 'OWNER';

  const connection = useQuery({
    queryKey: ['org-github'],
    queryFn: async () => (await apiClient.get('/org/github')).data as GitHubConnection,
    enabled: canManageOrg,
  });
  const available = useQuery({
    queryKey: ['org-github-repos'],
    queryFn: async () => (await apiClient.get('/org/github/repos')).data as InstallationRepo[],
    enabled: canManageOrg && !!connection.data?.connected,
    retry: false,
  });
  const afterRepoChange = () => {
    refresh();
    queryClient.invalidateQueries({ queryKey: ['org-github-repos'] });
  };

  const connect = useMutation({
    mutationFn: async () => (await apiClient.post('/org/github/connect')).data as { url: string },
    onSuccess: ({ url }) => { window.location.href = url; },
  });
  const disconnect = useMutation({
    mutationFn: async () => apiClient.delete('/org/github'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['org-github'] });
      queryClient.removeQueries({ queryKey: ['org-github-repos'] });
    },
  });
  const allow = useMutation({
    mutationFn: async (repo: string) => apiClient.post('/org/repos', { repo }),
    onSuccess: afterRepoChange,
  });

  const add = useMutation({
    mutationFn: async () => apiClient.post('/admin/repos', { repo: input.trim() }),
    onSuccess: () => { setInput(''); afterRepoChange(); },
  });
  const remove = useMutation({
    mutationFn: async (repo: string) => apiClient.delete(isPlatformAdmin ? '/admin/repos' : '/org/repos', { params: { repo } }),
    onSuccess: afterRepoChange,
  });

  const failure = add.error ?? remove.error ?? connect.error ?? disconnect.error ?? allow.error;
  const notYetAllowed = available.data?.filter(repo => !repo.allowed) ?? [];

  const confirmDisconnect = () => {
    if (window.confirm('Disconnect GitHub? Jobs in this organisation will stop until it is connected again.')) {
      disconnect.mutate();
    }
  };

  const confirmRemove = (repo: string) => {
    if (window.confirm(`Remove ${repo}? This organisation won't be able to start jobs on it.`)) {
      remove.mutate(repo);
    }
  };

  return (
    <>
      <PageHeader
        title="Repositories"
        description={`The repositories ${activeOrganization?.name ?? 'this organisation'} may use. Jobs on any other repository are refused.`}
      />

      {outcome && <div style={{ marginBottom: 16 }}><Alert tone={outcome.tone}>{outcome.text}</Alert></div>}
      {failure && <div style={{ marginBottom: 16 }}><Alert tone="danger">{errorMessage(failure)}</Alert></div>}

      {connection.data && (
        <section className="card status-card" style={{ marginBottom: 16 }}>
          <div className="github-connection">
            <div>
              <h2 className="status-heading" style={{ marginBottom: 4 }}><GitBranch size={16} /> GitHub</h2>
              <div className="status-hint">
                {connection.data.connected
                  ? 'Connected. GitOracle can only reach the repositories you installed the App on.'
                  : 'Not connected. Install the GitHub App on your repositories so jobs can read them and open pull requests.'}
              </div>
            </div>
            {isOwner && connection.data.canConnect && (
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <button type="button" className={`btn ${connection.data.connected ? 'btn-secondary' : 'btn-primary'}`} disabled={connect.isPending} onClick={() => connect.mutate()}>
                  {connection.data.connected ? 'Change repositories' : 'Connect GitHub'}
                </button>
                {connection.data.connected && !connection.data.shared && (
                  <button type="button" className="btn btn-ghost" disabled={disconnect.isPending} onClick={confirmDisconnect}>Disconnect</button>
                )}
              </div>
            )}
          </div>
          {isOwner && !connection.data.canConnect && !connection.data.connected && (
            <div className="status-hint" style={{ marginTop: 8 }}>The person who runs this GitOracle needs to set <code className="inline">GITHUB_APP_SLUG</code> first.</div>
          )}
          {!isOwner && !connection.data.connected && <div className="status-hint" style={{ marginTop: 8 }}>An owner of this organisation can connect it.</div>}
          {available.isError && <div className="form-error">Couldn't read the repositories from GitHub.</div>}
          {notYetAllowed.length > 0 && (
            <div style={{ marginTop: 14 }}>
              <div className="form-label">Available to add</div>
              {notYetAllowed.map(repo => (
                <div key={repo.fullName} className="status-row" style={{ gridTemplateColumns: 'minmax(0, 1fr) auto', alignItems: 'center' }}>
                  <span className="status-value mono">{repo.fullName}</span>
                  <button type="button" className="btn btn-secondary btn-sm" disabled={allow.isPending} onClick={() => allow.mutate(repo.fullName)}>Allow</button>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {isPlatformAdmin ? (
        <form className="card api-key-form" onSubmit={e => { e.preventDefault(); if (input.trim()) add.mutate(); }}>
          <div className="api-key-field-name">
            <label className="form-label" htmlFor="repo-add-input">Add a repository</label>
            <input
              id="repo-add-input"
              className="form-input"
              placeholder="owner/repo or https://github.com/owner/repo"
              autoComplete="off"
              spellCheck={false}
              value={input}
              onChange={e => setInput(e.target.value)}
            />
          </div>
          <button type="submit" className="btn btn-primary" disabled={!input.trim() || add.isPending}>
            {add.isPending ? 'Adding…' : 'Add'}
          </button>
        </form>
      ) : !canManageOrg && (
        <div style={{ marginBottom: 16 }}>
          <Alert tone="info">To add a repository, ask an admin of this organisation.</Alert>
        </div>
      )}

      {isLoading && <LoadingState />}
      {loadFailed && <Alert tone="danger">Couldn't load the repositories.</Alert>}
      {!isLoading && !loadFailed && repos.length === 0 && (
        <EmptyState icon={<FolderGit2 size={20} />} title="No repositories yet" description="Jobs can't start until one is allowed." />
      )}
      {repos.length > 0 && (
        <div className="table-container">
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>Repository</th><th>Active</th><th /></tr></thead>
              <tbody>
                {repos.map(repo => (
                  <tr key={repo.id}>
                    <td className="cell-primary">
                      {repo.fullName}{' '}
                      <a href={repo.url} target="_blank" rel="noreferrer" aria-label={`Open ${repo.fullName} on GitHub`} style={{ color: 'var(--text-muted)' }}>
                        <ExternalLink size={12} />
                      </a>
                    </td>
                    <td>
                      {activeRepo?.id === repo.id ? 'Active' : (
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setActiveRepo(repo.id)}>Set active</button>
                      )}
                    </td>
                    <td className="num">
                      {canManageOrg && (
                        <button type="button" className="btn btn-ghost btn-sm" disabled={remove.isPending} onClick={() => confirmRemove(repo.fullName)}>
                          Remove
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
