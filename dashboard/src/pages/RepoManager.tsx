import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { GitFork, Plus, Trash2, CheckCircle2, AlertCircle, Star, ExternalLink, Clock } from 'lucide-react';
import { useRepos } from '../context/RepoContext';

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.06 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 12 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function RepoManager() {
  const { repos, activeRepo, addRepo, removeRepo, setActiveRepo } = useRepos();
  const [input, setInput] = useState('');
  const [error, setError] = useState('');
  const [added, setAdded] = useState('');

  const handleAdd = () => {
    setError('');
    if (!input.trim()) return;
    const result = addRepo(input.trim());
    if (!result) {
      setError('Invalid GitHub URL. Use "https://github.com/owner/repo" or "owner/repo".');
      return;
    }
    setAdded(result.fullName);
    setInput('');
    setTimeout(() => setAdded(''), 3000);
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleAdd();
  };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 800 }}>
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <div style={{
            width: 40, height: 40, borderRadius: 10,
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
            display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <GitFork size={20} color="#fff" />
          </div>
          <h1 className="page-title" style={{ marginBottom: 0 }}>My Repos</h1>
        </div>
        <p className="page-description" style={{ marginLeft: 52 }}>
          Register GitHub repositories. The active repo is pre-filled across all GitOracle pages.
        </p>
      </motion.div>

      {/* Add Repo Card */}
      <motion.div variants={itemVariants} className="card" style={{ padding: '24px 28px', marginBottom: 20 }}>
        <h2 style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Plus size={16} color="var(--accent)" /> Add Repository
        </h2>
        <div style={{ display: 'flex', gap: 10 }}>
          <input
            id="repo-add-input"
            type="text"
            value={input}
            onChange={e => { setInput(e.target.value); setError(''); }}
            onKeyDown={handleKey}
            placeholder="https://github.com/owner/repo  or  owner/repo"
            style={{
              flex: 1, padding: '10px 14px',
              background: 'var(--bg-lighter)', border: `1px solid ${error ? 'var(--danger)' : 'var(--border-subtle)'}`,
              borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
              outline: 'none', transition: 'border-color 0.2s'
            }}
            onFocus={e => { if (!error) e.currentTarget.style.borderColor = 'var(--accent)'; }}
            onBlur={e => { if (!error) e.currentTarget.style.borderColor = 'var(--border-subtle)'; }}
          />
          <button
            id="repo-add-btn"
            className="btn btn-primary"
            onClick={handleAdd}
            style={{ display: 'flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}
          >
            <Plus size={15} /> Add Repo
          </button>
        </div>

        <AnimatePresence>
          {error && (
            <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
              style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, color: 'var(--danger)', fontSize: '0.85rem' }}>
              <AlertCircle size={14} /> {error}
            </motion.div>
          )}
          {added && (
            <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
              style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, color: 'var(--success)', fontSize: '0.85rem' }}>
              <CheckCircle2 size={14} /> Added <strong>{added}</strong> successfully!
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>

      {/* Repos List */}
      {repos.length === 0 ? (
        <motion.div variants={itemVariants} className="card" style={{ padding: '48px 32px', textAlign: 'center' }}>
          <GitFork size={40} style={{ color: 'var(--text-muted)', marginBottom: 16, opacity: 0.4 }} />
          <p style={{ color: 'var(--text-muted)', fontSize: '1rem', marginBottom: 4 }}>No repos added yet</p>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>Add a GitHub repo above to get started</p>
        </motion.div>
      ) : (
        <motion.div variants={containerVariants} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <AnimatePresence>
            {repos.map(repo => {
              const isActive = activeRepo?.id === repo.id;
              return (
                <motion.div
                  key={repo.id}
                  variants={itemVariants}
                  layout
                  exit={{ opacity: 0, scale: 0.96, transition: { duration: 0.15 } }}
                  className="card"
                  style={{
                    padding: '18px 24px',
                    border: isActive ? '1px solid var(--accent)' : '1px solid var(--border-subtle)',
                    background: isActive ? 'rgba(99,102,241,0.05)' : undefined,
                    display: 'flex', alignItems: 'center', gap: 16
                  }}
                >
                  {/* Status dot */}
                  <div style={{
                    width: 10, height: 10, borderRadius: '50%', flexShrink: 0,
                    background: isActive ? 'var(--success)' : 'var(--text-muted)',
                    boxShadow: isActive ? '0 0 8px var(--success)' : 'none',
                    transition: 'all 0.3s'
                  }} />

                  {/* Repo info */}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 3 }}>
                      <span style={{ fontWeight: 600, color: 'var(--text-primary)', fontSize: '0.95rem' }}>
                        {repo.fullName}
                      </span>
                      {isActive && (
                        <span style={{
                          fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 99,
                          background: 'rgba(99,102,241,0.2)', color: 'var(--accent)', letterSpacing: '0.04em'
                        }}>ACTIVE</span>
                      )}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', fontFamily: 'monospace' }}>
                        {repo.url}
                      </span>
                      <a href={repo.url} target="_blank" rel="noreferrer"
                        style={{ color: 'var(--text-muted)', display: 'flex', alignItems: 'center' }}>
                        <ExternalLink size={12} />
                      </a>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 4 }}>
                      <Clock size={10} style={{ color: 'var(--text-muted)' }} />
                      <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                        Added {new Date(repo.addedAt).toLocaleDateString()}
                      </span>
                    </div>
                  </div>

                  {/* Actions */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                    {!isActive && (
                      <button
                        id={`set-active-${repo.id}`}
                        className="btn btn-outline"
                        onClick={() => setActiveRepo(repo.id)}
                        style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px', fontSize: '0.8rem' }}
                      >
                        <Star size={13} /> Set Active
                      </button>
                    )}
                    {isActive && (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
                        fontSize: '0.8rem', color: 'var(--success)', fontWeight: 500 }}>
                        <CheckCircle2 size={13} /> Active
                      </div>
                    )}
                    <button
                      id={`remove-repo-${repo.id}`}
                      onClick={() => removeRepo(repo.id)}
                      style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        color: 'var(--text-muted)', padding: 6, borderRadius: 6,
                        display: 'flex', alignItems: 'center',
                        transition: 'color 0.2s'
                      }}
                      onMouseEnter={e => (e.currentTarget.style.color = 'var(--danger)')}
                      onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-muted)')}
                    >
                      <Trash2 size={15} />
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
