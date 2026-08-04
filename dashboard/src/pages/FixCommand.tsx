import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Wand2, GitBranch, FileText, ChevronRight, CheckCircle2, Loader2, AlertCircle, Plus } from 'lucide-react';
import { apiClient } from '../api/client';
import { useRepos } from '../context/RepoContext';

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.07 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

type Status = 'idle' | 'loading' | 'success' | 'error';

export default function FixCommand() {
  const navigate = useNavigate();
  const { repos, activeRepo } = useRepos();
  const [searchParams] = useSearchParams();

  // Pre-fill from CommitDetail "Apply Fix as PR" navigation
  const paramIssue  = searchParams.get('issueDescription') ?? '';
  const paramRepo   = searchParams.get('repo') ?? '';

  const [repoUrl, setRepoUrl]       = useState(paramRepo ? `https://github.com/${paramRepo}` : (activeRepo?.url || ''));
  const [issueDesc, setIssueDesc]   = useState(paramIssue);
  const [targetRepo, setTargetRepo] = useState(paramRepo || activeRepo?.fullName || '');
  const [status, setStatus]         = useState<Status>('idle');
  const [errorMsg, setErrorMsg]     = useState('');
  const [jobId, setJobId]           = useState('');

  // Keep source/target pre-filled with active repo

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!repoUrl.trim() || !issueDesc.trim()) return;

    setStatus('loading');
    setErrorMsg('');

    try {
      const { data } = await apiClient.post('/trigger', {
        repoUrl: repoUrl.trim(),
        issueDescription: issueDesc.trim(),
        targetRepo: targetRepo.trim()
      });
      setJobId(data.jobId);
      setStatus('success');
    } catch (err: any) {
      setStatus('error');
      setErrorMsg(err?.response?.data?.error || err?.message || 'Request failed');
    }
  };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 720, margin: '0 auto' }}>
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <div style={{
            width: 40, height: 40, borderRadius: 10,
            background: 'linear-gradient(135deg, var(--accent), #a855f7)',
            display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <Wand2 size={20} color="#fff" />
          </div>
          <h1 className="page-title" style={{ marginBottom: 0 }}>Ask GitOracle to Fix</h1>
        </div>
        <p className="page-description" style={{ marginLeft: 52 }}>
          Describe the issue in plain English — GitOracle will plan, fix, test, and open a PR autonomously.
        </p>
      </motion.div>

      <AnimatePresence mode="wait">
        {status === 'success' ? (
          <motion.div
            key="success"
            initial={{ opacity: 0, scale: 0.96 }}
            animate={{ opacity: 1, scale: 1 }}
            className="card"
            style={{ padding: '40px 32px', textAlign: 'center' }}
          >
            <div style={{
              width: 64, height: 64, borderRadius: '50%',
              background: 'rgba(34,197,94,0.15)', border: '2px solid var(--success)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              margin: '0 auto 20px'
            }}>
              <CheckCircle2 size={32} color="var(--success)" />
            </div>
            <h2 style={{ color: 'var(--text-primary)', marginBottom: 8 }}>Fix Job Queued!</h2>
            <p style={{ color: 'var(--text-muted)', marginBottom: 24 }}>
              GitOracle is now analyzing, fixing, and testing your issue. A PR will be opened when complete.
            </p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap' }}>
              <button
                className="btn btn-primary"
                onClick={() => navigate('/job/' + jobId)}
              >
                Watch Live Progress →
              </button>
              <button
                className="btn btn-outline"
                onClick={() => { setStatus('idle'); setRepoUrl(''); setIssueDesc(''); setTargetRepo(''); setJobId(''); }}
              >
                Submit Another
              </button>
            </div>
          </motion.div>
        ) : (
          <motion.form key="form" variants={itemVariants} onSubmit={handleSubmit}>
            <div className="card" style={{ padding: '28px 32px', marginBottom: 16 }}>
              <h2 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 20 }}>
                <FileText size={16} style={{ marginRight: 8, verticalAlign: 'middle' }} />
                Issue Details
              </h2>

              <div style={{ marginBottom: 20 }}>
                <label style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                  Source Repository *
                </label>
                {repos.length > 0 ? (
                  <select
                    id="repo-url-input"
                    value={repoUrl}
                    onChange={e => setRepoUrl(e.target.value)}
                    required
                    style={{
                      width: '100%', padding: '10px 14px',
                      background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
                      borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
                      boxSizing: 'border-box', outline: 'none', cursor: 'pointer'
                    }}
                  >
                    <option value="">Select a repository...</option>
                    {repos.map(r => (
                      <option key={r.id} value={r.url}>{r.fullName}{r.id === activeRepo?.id ? ' (active)' : ''}</option>
                    ))}
                  </select>
                ) : (
                  <>
                    <input
                      id="repo-url-input"
                      type="text"
                      value={repoUrl}
                      onChange={e => setRepoUrl(e.target.value)}
                      placeholder="https://github.com/owner/repo"
                      required
                      style={{
                        width: '100%', padding: '10px 14px',
                        background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
                        borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
                        boxSizing: 'border-box', outline: 'none', transition: 'border-color 0.2s'
                      }}
                      onFocus={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
                      onBlur={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
                    />
                    <div style={{ marginTop: 6, fontSize: '0.78rem', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 4 }}>
                      <Plus size={11} />
                      <a href="#" onClick={e => { e.preventDefault(); navigate('/repos'); }} style={{ color: 'var(--accent)', textDecoration: 'none' }}>Add repos to My Repos</a> for quick selection
                    </div>
                  </>
                )}
              </div>

              <div style={{ marginBottom: 20 }}>
                <label style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                  Describe the Issue / Fix Instruction *
                </label>
                <textarea
                  id="issue-desc-input"
                  value={issueDesc}
                  onChange={e => setIssueDesc(e.target.value)}
                  placeholder="e.g. The payment service throws a NullPointerException when the user object is null. Fix it by adding a null check before accessing user.getId()."
                  required
                  rows={5}
                  style={{
                    width: '100%', padding: '10px 14px',
                    background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
                    borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
                    boxSizing: 'border-box', outline: 'none', resize: 'vertical',
                    fontFamily: 'inherit', lineHeight: 1.6,
                    transition: 'border-color 0.2s'
                  }}
                  onFocus={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
                  onBlur={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
                />
              </div>

              <div>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                  <GitBranch size={12} />
                  Open PR on Repo (optional — defaults to source repo)
                </label>
                {repos.length > 0 ? (
                  <select
                    id="target-repo-input"
                    value={targetRepo}
                    onChange={e => setTargetRepo(e.target.value)}
                    style={{
                      width: '100%', padding: '10px 14px',
                      background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
                      borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
                      boxSizing: 'border-box', outline: 'none', cursor: 'pointer'
                    }}
                  >
                    <option value="">Same as source repo</option>
                    {repos.map(r => (
                      <option key={r.id} value={r.fullName}>{r.fullName}{r.id === activeRepo?.id ? ' (active)' : ''}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    id="target-repo-input"
                    type="text"
                    value={targetRepo}
                    onChange={e => setTargetRepo(e.target.value)}
                    placeholder="owner/repo  (e.g. bettercallok/chillcall)"
                    style={{
                      width: '100%', padding: '10px 14px',
                      background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
                      borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
                      boxSizing: 'border-box', outline: 'none', transition: 'border-color 0.2s'
                    }}
                    onFocus={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
                    onBlur={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
                  />
                )}
              </div>
            </div>

            {status === 'error' && (
              <motion.div
                initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  padding: '12px 16px', borderRadius: 8,
                  background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)',
                  color: 'var(--danger)', marginBottom: 16, fontSize: '0.9rem'
                }}
              >
                <AlertCircle size={16} />
                {errorMsg}
              </motion.div>
            )}

            <motion.div variants={itemVariants} style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                id="submit-fix-btn"
                type="submit"
                className="btn btn-primary"
                disabled={status === 'loading' || !repoUrl.trim() || !issueDesc.trim()}
                style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 28px', fontSize: '0.95rem' }}
              >
                {status === 'loading' ? (
                  <><Loader2 size={16} className="spinner" /> Queuing Fix...</>
                ) : (
                  <>Launch Fix Agent <ChevronRight size={16} /></>
                )}
              </button>
            </motion.div>
          </motion.form>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
