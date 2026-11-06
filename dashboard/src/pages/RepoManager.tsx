import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { ExternalLink, FolderGit2 } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useRepos } from '../context/RepoContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

const errorMessage = (error: unknown): string => {
  const data = (error as { response?: { data?: { error?: string; message?: string } } }).response?.data;
  return data?.error && data.error !== 'Forbidden' ? data.error : data?.message ?? 'Something went wrong. Please try again.';
};

export default function RepoManager() {
  const { isPlatformAdmin, activeOrganization } = useAuth();
  const { repos, activeRepo, setActiveRepo, isLoading, loadFailed, refresh } = useRepos();
  const [input, setInput] = useState('');

  const add = useMutation({
    mutationFn: async () => apiClient.post('/admin/repos', { repo: input.trim() }),
    onSuccess: () => { setInput(''); refresh(); },
  });
  const remove = useMutation({
    mutationFn: async (repo: string) => apiClient.delete('/admin/repos', { params: { repo } }),
    onSuccess: refresh,
  });

  const failure = add.error ?? remove.error;

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

      {failure && <div style={{ marginBottom: 16 }}><Alert tone="danger">{errorMessage(failure)}</Alert></div>}

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
      ) : (
        <div style={{ marginBottom: 16 }}>
          <Alert tone="info">To add a repository, ask the person who runs this GitOracle.</Alert>
        </div>
      )}

      {isLoading && <LoadingState />}
      {loadFailed && <Alert tone="danger">Couldn't load the repositories.</Alert>}
      {!isLoading && !loadFailed && repos.length === 0 && (
        <EmptyState icon={<FolderGit2 size={20} />} title="No repositories yet" description="Jobs can't start until one is added." />
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
                      {isPlatformAdmin && (
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
