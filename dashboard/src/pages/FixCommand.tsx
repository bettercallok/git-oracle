import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { GitBranch, ArrowRight, CheckCircle2, Loader2, Search, Zap, FolderGit2, GitPullRequest, Wand2 } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useRepos } from '../context/RepoContext';
import { containerVariants, itemVariants } from '../utils/motion';
import { errorMessage } from '../utils/format';
import { PageHeader, Alert, EmptyState } from '../components/ui';

type Status = 'idle' | 'loading' | 'success' | 'error';

export default function FixCommand() {
  const { canWrite } = useAuth();
  const navigate = useNavigate();
  const { repos, activeRepo } = useRepos();
  const [searchParams] = useSearchParams();

  // Pre-fill from CommitDetail "Apply Fix as PR" navigation
  const paramIssue  = searchParams.get('issueDescription') ?? '';
  const paramRepo   = searchParams.get('repo') ?? '';

  const [repoUrl, setRepoUrl]       = useState(paramRepo ? `https://github.com/${paramRepo}` : (activeRepo?.url || ''));
  const [issueDesc, setIssueDesc]   = useState(paramIssue);
  const [targetRepo, setTargetRepo] = useState(paramRepo || activeRepo?.fullName || '');
  // Empty = clone/PR against the repo's actual default branch. GitOracle previously
  // had no way to target anything else — the source it reads, tests, and opens a PR
  // against was always whatever `git clone` picks with no branch specified.
  const [branch, setBranch]         = useState('');
  const [status, setStatus]         = useState<Status>('idle');

  // The repositories arrive from the server after this page first renders.
  useEffect(() => {
    if (!activeRepo) return;
    setRepoUrl(current => current || activeRepo.url);
    setTargetRepo(current => current || activeRepo.fullName);
  }, [activeRepo]);
  const [errorMsg, setErrorMsg]     = useState('');
  const [jobId, setJobId]           = useState('');
  // Default on: run the real Investigator → Planner → Fixer chain. Turning it off
  // sends the request straight to the Fixer with a synthesised plan — much cheaper
  // and faster, but no root-cause analysis and no file context for the Fixer.
  const [investigateFirst, setInvestigateFirst] = useState(true);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!repoUrl.trim() || !issueDesc.trim()) return;

    setStatus('loading');
    setErrorMsg('');

    try {
      const { data } = await apiClient.post('/trigger', {
        repoUrl: repoUrl.trim(),
        issueDescription: issueDesc.trim(),
        targetRepo: targetRepo.trim(),
        branch: branch.trim(),
        investigateFirst
      });
      setJobId(data.jobId);
      setStatus('success');
    } catch (err) {
      setStatus('error');
      setErrorMsg(errorMessage(err, 'Request failed'));
    }
  };

  const reset = () => { setStatus('idle'); setRepoUrl(''); setIssueDesc(''); setTargetRepo(''); setBranch(''); setJobId(''); };

  if (!canWrite) {
    return (
      <EmptyState
        icon={<Wand2 size={20} />}
        title="Your role can't start jobs"
        description="You're a viewer in this organisation. Ask an admin if you need more access."
      />
    );
  }

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 760 }}>
      <PageHeader
        title="Ask to fix"
        description="Describe the bug in plain English. GitOracle finds the cause, writes a patch, runs your tests and opens a pull request."
      />

      <AnimatePresence mode="wait">
        {status === 'success' ? (
          <motion.div
            key="success"
            initial={{ opacity: 0, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            className="card"
            style={{ padding: '44px 32px', textAlign: 'center' }}
          >
            <div className="empty-icon success" style={{ margin: '0 auto 18px', width: 52, height: 52 }}>
              <CheckCircle2 size={24} />
            </div>
            <h2 style={{ fontSize: '1.25rem' }}>Job queued</h2>
            <p style={{ color: 'var(--text-secondary)', margin: '8px auto 6px', maxWidth: '46ch' }}>
              {investigateFirst
                ? 'The investigator is looking through the history now. You can watch each stage as it happens.'
                : 'The fixer has your instruction and is writing a patch now.'}
            </p>
            <p className="mono muted" style={{ fontSize: '0.8rem', marginBottom: 24 }}>job {jobId.substring(0, 8)}</p>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }}>
              <button className="btn btn-primary" onClick={() => navigate('/job/' + jobId)}>
                Follow progress <ArrowRight size={15} />
              </button>
              <button className="btn" onClick={reset}>Report another bug</button>
            </div>
          </motion.div>
        ) : (
          <motion.form key="form" variants={itemVariants} onSubmit={handleSubmit}>
            <div className="card" style={{ padding: '24px 26px', marginBottom: 16 }}>
              <div className="field">
                <label className="form-label" htmlFor="repo-url-input"><FolderGit2 size={14} /> Repository</label>
                {repos.length > 0 ? (
                  <select id="repo-url-input" className="form-select" value={repoUrl} onChange={e => setRepoUrl(e.target.value)} required>
                    <option value="">Choose a repository…</option>
                    {repos.map(r => (
                      <option key={r.id} value={r.url}>{r.fullName}{r.id === activeRepo?.id ? ' (active)' : ''}</option>
                    ))}
                  </select>
                ) : (
                  <>
                    <input
                      id="repo-url-input"
                      type="text"
                      className="form-input mono-input"
                      value={repoUrl}
                      onChange={e => setRepoUrl(e.target.value)}
                      placeholder="https://github.com/owner/repo"
                      required
                    />
                    <div className="form-hint">
                      <Link to="/repos" className="link">Save repositories</Link> to pick them from a list next time.
                    </div>
                  </>
                )}
              </div>

              <div className="field">
                <label className="form-label" htmlFor="issue-desc-input">What's wrong?</label>
                <textarea
                  id="issue-desc-input"
                  className="form-textarea"
                  value={issueDesc}
                  onChange={e => setIssueDesc(e.target.value)}
                  placeholder="e.g. PaymentService throws a NullPointerException when the user is null. Add a null check before user.getId()."
                  required
                  rows={5}
                />
                <div className="form-hint">
                  Include the error message, where it happens, and — if you know it — how you'd fix it.
                </div>
              </div>

              <div className="grid-2">
                <div className="field" style={{ marginBottom: 0 }}>
                  <label className="form-label" htmlFor="target-repo-input">
                    <GitPullRequest size={14} /> Open the PR on <span className="optional">· optional</span>
                  </label>
                  {repos.length > 0 ? (
                    <select id="target-repo-input" className="form-select" value={targetRepo} onChange={e => setTargetRepo(e.target.value)}>
                      <option value="">Same repository</option>
                      {repos.map(r => (
                        <option key={r.id} value={r.fullName}>{r.fullName}{r.id === activeRepo?.id ? ' (active)' : ''}</option>
                      ))}
                    </select>
                  ) : (
                    <input
                      id="target-repo-input"
                      type="text"
                      className="form-input mono-input"
                      value={targetRepo}
                      onChange={e => setTargetRepo(e.target.value)}
                      placeholder="owner/repo"
                    />
                  )}
                </div>

                <div className="field" style={{ marginBottom: 0 }}>
                  <label className="form-label" htmlFor="branch-input">
                    <GitBranch size={14} /> Branch <span className="optional">· optional</span>
                  </label>
                  <input
                    id="branch-input"
                    type="text"
                    className="form-input mono-input"
                    value={branch}
                    onChange={e => setBranch(e.target.value)}
                    placeholder="default branch"
                  />
                </div>
              </div>
              <div className="form-hint">
                GitOracle reads, tests and opens its pull request against this branch. Leave it empty to use the repository's default.
              </div>
            </div>

            <motion.div variants={itemVariants} style={{ marginBottom: 16 }}>
              <div className="form-label" style={{ marginBottom: 10 }}>How should it work?</div>
              <div className="grid-2" role="radiogroup" aria-label="Pipeline mode">
                <label className={`option-card ${investigateFirst ? 'selected' : ''}`}>
                  <input id="investigate-first" type="radio" name="mode" checked={investigateFirst}
                         onChange={() => setInvestigateFirst(true)} style={{ marginTop: 3, accentColor: 'var(--accent)' }} />
                  <div>
                    <div className="option-title"><Search size={14} /> Investigate first <span className="badge badge-accent no-dot" style={{ height: 18, fontSize: '0.68rem' }}>Recommended</span></div>
                    <div className="option-desc">
                      Finds the commit that caused the bug, scopes a plan, then fixes it. Slower (~25s) and uses more tokens.
                    </div>
                  </div>
                </label>
                <label className={`option-card ${!investigateFirst ? 'selected' : ''}`}>
                  <input type="radio" name="mode" checked={!investigateFirst}
                         onChange={() => setInvestigateFirst(false)} style={{ marginTop: 3, accentColor: 'var(--accent)' }} />
                  <div>
                    <div className="option-title"><Zap size={14} /> Fix directly</div>
                    <div className="option-desc">
                      Sends your instruction straight to the fixer, with no root-cause search. Faster (~8s) and cheaper — best when you already know the fix.
                    </div>
                  </div>
                </label>
              </div>
            </motion.div>

            {status === 'error' && (
              <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} style={{ marginBottom: 16 }}>
                <Alert tone="danger" title="Couldn't start the job">{errorMsg}</Alert>
              </motion.div>
            )}

            <motion.div variants={itemVariants} style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                id="submit-fix-btn"
                type="submit"
                className="btn btn-primary btn-lg"
                disabled={status === 'loading' || !repoUrl.trim() || !issueDesc.trim()}
              >
                {status === 'loading'
                  ? <><Loader2 size={16} className="spinner" /> Starting…</>
                  : <>Start fixing <ArrowRight size={16} /></>}
              </button>
            </motion.div>
          </motion.form>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
