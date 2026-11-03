import { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { FolderGit2, Plus, Trash2, ExternalLink, Check, GitCommitHorizontal, Wand2 } from 'lucide-react';
import { useRepos } from '../context/RepoContext';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader, Alert, EmptyState, Avatar } from '../components/ui';

export default function RepoManager() {
  const { repos, activeRepo, addRepo, removeRepo, setActiveRepo } = useRepos();
  const [input, setInput] = useState('');
  const [error, setError] = useState('');
  const [added, setAdded] = useState('');

  const handleAdd = (e?: React.FormEvent) => {
    e?.preventDefault();
    setError('');
    if (!input.trim()) return;
    const result = addRepo(input.trim());
    if (!result) {
      setError('That doesn\'t look like a GitHub repository. Use "owner/repo" or "https://github.com/owner/repo".');
      return;
    }
    setAdded(result.fullName);
    setInput('');
    setTimeout(() => setAdded(''), 3000);
  };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 820 }}>
      <PageHeader
        title="Repositories"
        description="Repositories you work with. The active one is pre-selected on Ask to fix and Commits. Saved in this browser only."
      />

      <motion.form variants={itemVariants} className="card" style={{ marginBottom: 20 }} onSubmit={handleAdd}>
        <label className="form-label" htmlFor="repo-add-input">Add a repository</label>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <div className="input-wrap" style={{ flex: 1, minWidth: 240 }}>
            <FolderGit2 size={15} className="input-icon" />
            <input
              id="repo-add-input"
              type="text"
              className={`form-input mono-input ${error ? 'invalid' : ''}`}
              value={input}
              onChange={e => { setInput(e.target.value); setError(''); }}
              placeholder="owner/repo"
              spellCheck={false}
              aria-invalid={!!error}
            />
          </div>
          <button id="repo-add-btn" type="submit" className="btn btn-primary" style={{ height: 38 }} disabled={!input.trim()}>
            <Plus size={15} /> Add
          </button>
        </div>
        <div className="form-hint">The GitOracle GitHub App must be installed on it for commits and pull requests to work.</div>

        <AnimatePresence>
          {error && (
            <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginTop: 12 }}>
              <Alert tone="danger">{error}</Alert>
            </motion.div>
          )}
          {added && (
            <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginTop: 12 }}>
              <Alert tone="success">Added <strong>{added}</strong>.</Alert>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.form>

      {repos.length === 0 ? (
        <motion.div variants={itemVariants} className="card dashed">
          <EmptyState
            icon={<FolderGit2 size={20} />}
            title="No repositories yet"
            description="Add one above. The first repository you add becomes the active one."
          />
        </motion.div>
      ) : (
        <motion.div variants={itemVariants} className="card flush">
          <div className="card-header">
            <div className="card-title">Saved <span className="muted" style={{ fontWeight: 400 }}>{repos.length}</span></div>
          </div>
          <AnimatePresence initial={false}>
            {repos.map((repo, i) => {
              const isActive = activeRepo?.id === repo.id;
              const [owner] = repo.fullName.split('/');
              return (
                <motion.div
                  key={repo.id}
                  layout
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0, height: 0, transition: { duration: 0.16 } }}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 14, padding: '14px 20px',
                    borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)',
                    background: isActive ? 'var(--accent-soft)' : undefined,
                  }}
                >
                  <Avatar name={owner} seed={owner} size={32} />

                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span className="truncate" style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{repo.fullName}</span>
                      {isActive && <span className="badge badge-accent">Active</span>}
                    </div>
                    <div className="muted" style={{ fontSize: '0.8rem', marginTop: 2 }}>
                      Added {new Date(repo.addedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
                    {isActive ? (
                      <>
                        <Link to="/commits" className="btn btn-ghost btn-sm hide-mobile"><GitCommitHorizontal size={14} /> Commits</Link>
                        <Link to="/fix" className="btn btn-ghost btn-sm hide-mobile"><Wand2 size={14} /> Fix</Link>
                      </>
                    ) : (
                      <button id={`set-active-${repo.id}`} className="btn btn-sm" onClick={() => setActiveRepo(repo.id)}>
                        <Check size={14} /> Make active
                      </button>
                    )}
                    <a href={repo.url} target="_blank" rel="noreferrer" className="btn btn-ghost btn-sm btn-icon"
                       title="Open on GitHub" aria-label={`Open ${repo.fullName} on GitHub`}>
                      <ExternalLink size={14} />
                    </a>
                    <button
                      id={`remove-repo-${repo.id}`}
                      className="btn btn-ghost btn-sm btn-icon"
                      onClick={() => removeRepo(repo.id)}
                      title="Remove"
                      aria-label={`Remove ${repo.fullName}`}
                      style={{ color: 'var(--text-muted)' }}
                      onMouseEnter={e => (e.currentTarget.style.color = 'var(--danger)')}
                      onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-muted)')}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </motion.div>
              );
            })}
          </AnimatePresence>
        </motion.div>
      )}
    </motion.div>
  );
}
