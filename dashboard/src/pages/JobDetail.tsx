import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, CheckCircle2, Loader2, AlertCircle, GitPullRequest, RefreshCw, Send } from 'lucide-react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../api/client';

/** Safely format a timestamp from the backend.
 *  Handles: null, undefined, epoch (Jan 1 1970) and valid ISO strings.
 *  Returns a human-readable local date+time string. */
const formatTime = (raw: string | null | undefined): string => {
  if (!raw) return '—';
  const d = new Date(raw);
  if (isNaN(d.getTime())) return '—';
  // Epoch or near-epoch means the DB had no timestamp yet
  if (d.getFullYear() < 2000) return '—';
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  });
};

interface Job {
  id: string;
  repo: string;
  errorMessage: string;
  state: string;
  tokensUsed: number;
  createdAt: string;
  prUrl?: string;
}

const fetchJob = async (id: string): Promise<Job> => {
  const { data } = await apiClient.get(`/jobs/${id}`);
  return data;
};

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function JobDetail() {
  const { jobId } = useParams<{ jobId: string }>();
  const queryClient = useQueryClient();
  const [instructions, setInstructions] = useState('');
  const [feedbackSent, setFeedbackSent] = useState(false);

  const { data: job, isLoading, isError } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => fetchJob(jobId!),
    enabled: !!jobId,
    refetchInterval: 3000,
  });

  const feedbackMutation = useMutation({
    mutationFn: (instructions: string) =>
      apiClient.post(`/jobs/${jobId}/feedback`, { instructions }),
    onSuccess: () => {
      setFeedbackSent(true);
      setInstructions('');
      queryClient.invalidateQueries({ queryKey: ['job', jobId] });
      setTimeout(() => setFeedbackSent(false), 5000);
    }
  });

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '4rem' }}>
        <Loader2 size={32} className="spinner" style={{ color: 'var(--accent)' }} />
      </div>
    );
  }

  if (isError || !job) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--danger)' }}>
        <AlertCircle size={32} style={{ marginBottom: 16 }} />
        <h2>Error loading job details</h2>
        <p>Could not find job {jobId}</p>
        <Link to="/" className="btn btn-outline" style={{ marginTop: 16 }}>Back to Feed</Link>
      </div>
    );
  }

  // Support both 'status' and 'state' field names
  const status = (job as any).status || job.state;
  const isSuccess = status === 'SUCCESS' || status === 'PR_OPENED';
  const isReady   = ['PR_OPENED', 'SUCCESS', 'FAILED', 'ESCALATED', 'INVESTIGATING', 'PLANNING', 'TESTING', 'REGENERATING', 'QUEUED'].includes(status);
  const isEscalated = status === 'ESCALATED';

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 8 }}>
          <Link to="/" className="btn btn-outline" style={{ padding: '6px 10px', textDecoration: 'none' }}>
            <ArrowLeft size={16} />
          </Link>
          <h1 className="page-title" style={{ marginBottom: 0 }}>Job {job.id.substring(0,8)}</h1>
          <span className={`badge ${isSuccess ? 'badge-success' : 'badge-warning'}`} style={{ padding: '4px 8px' }}>
            {isSuccess ? <CheckCircle2 size={12} style={{ marginRight: 2 }} /> : <Loader2 size={12} style={{ marginRight: 2 }} />} 
            {status}
          </span>
        </div>
        <p className="page-description" style={{ marginLeft: 50 }}>Full pipeline timeline</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Repository</div>
          <div style={{ fontSize: '1.1rem', fontWeight: 500, color: 'var(--text-primary)' }}>{job.repo.replace('https://github.com/','')}</div>
        </div>
        <div className="card">
          <div className="stat-label">Tokens Used</div>
          <div className="mono" style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>{job.tokensUsed ?? 0}</div>
        </div>
        <div className="card">
          <div className="stat-label">Status</div>
          <div className="stat-value">{status}</div>
        </div>
        <div className="card">
          <div className="stat-label">When</div>
          <div className="stat-value" style={{ fontSize: '0.9rem' }}>{formatTime(job.createdAt)}</div>
        </div>
      </motion.div>

      {/* PR URL Banner */}
      {job.prUrl && (
        <motion.div variants={itemVariants}
          style={{
            display: 'flex', alignItems: 'center', gap: 12,
            padding: '14px 20px', borderRadius: 10, marginBottom: 16,
            background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.3)'
          }}
        >
          <GitPullRequest size={18} color="var(--success)" />
          <span style={{ color: 'var(--text-primary)', fontWeight: 500 }}>Pull Request Opened:</span>
          <a href={job.prUrl} target="_blank" rel="noreferrer"
            style={{ color: 'var(--accent)', textDecoration: 'none', fontFamily: 'monospace', fontSize: '0.9rem' }}>
            {job.prUrl}
          </a>
        </motion.div>
      )}

      <motion.div variants={itemVariants} className="card" style={{ padding: '28px 32px' }}>
        <h2 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 24 }}>Pipeline Timeline</h2>
        <div className="timeline">
            <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }}>
              <div className="timeline-dot" />
              <div className="timeline-time">{formatTime(job.createdAt)}</div>
              <div className="timeline-title">Job Created</div>
              <div className="timeline-body">GitOracle orchestrator began processing the event.</div>
            </motion.div>

            {['INVESTIGATING','PLANNING','TESTING','PR_OPENED','SUCCESS','ESCALATED','REGENERATING'].includes(status) && (
              <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.1 }}>
                <div className="timeline-dot" style={{ borderColor: 'var(--accent)' }} />
                <div className="timeline-time">AI Phase</div>
                <div className="timeline-title">Planner → Fixer → Test Runner</div>
                <div className="timeline-body">Agents are collaborating to generate and validate a fix.</div>
              </motion.div>
            )}

            {isSuccess && (
              <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.2 }}>
                <div className="timeline-dot" style={{ borderColor: 'var(--success)' }} />
                <div className="timeline-time">Completed</div>
                <div className="timeline-title">Pull Request Opened</div>
                <div className="timeline-body">GitOracle autonomously fixed the issue and opened a PR.</div>
              </motion.div>
            )}

            {isEscalated && (
              <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.2 }}>
                <div className="timeline-dot" style={{ borderColor: 'var(--danger, #ef4444)' }} />
                <div className="timeline-time">Escalated</div>
                <div className="timeline-title">Fixer Could Not Produce a Valid Patch</div>
                <div className="timeline-body">The agent exhausted its attempts without a fix that passed guardrails or tests. Review the escalation queue, or submit new instructions below to retry.</div>
              </motion.div>
            )}

            {status === 'REGENERATING' && (
              <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.2 }}>
                <div className="timeline-dot" style={{ borderColor: '#f59e0b', animationName: 'pulse' }} />
                <div className="timeline-time">Now</div>
                <div className="timeline-title">Regenerating Fix</div>
                <div className="timeline-body">Applying your instructions and generating a new patch...</div>
              </motion.div>
            )}
        </div>
      </motion.div>

      {/* Human Feedback Panel */}
      {isReady && (
        <motion.div variants={itemVariants} className="card" style={{ padding: '28px 32px', marginTop: 16 }}>
          <h2 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 8 }}>
            <RefreshCw size={16} color="var(--accent)" />
            Regenerate Fix
          </h2>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginBottom: 18 }}>
            Not satisfied with the patch? Tell GitOracle exactly how you want it fixed and it will generate a new PR.
          </p>

          <AnimatePresence>
            {feedbackSent && (
              <motion.div
                initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  padding: '10px 14px', borderRadius: 8, marginBottom: 16,
                  background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.3)',
                  color: 'var(--success)', fontSize: '0.9rem'
                }}
              >
                <CheckCircle2 size={16} />
                Instructions sent! A new fix is being generated and will open as a new PR.
              </motion.div>
            )}
          </AnimatePresence>

          <textarea
            id="feedback-instructions"
            value={instructions}
            onChange={e => setInstructions(e.target.value)}
            placeholder="e.g. Instead of catching the exception, fix the root cause by checking for null before calling the method."
            rows={4}
            style={{
              width: '100%', padding: '10px 14px',
              background: 'var(--bg-lighter)', border: '1px solid var(--border-subtle)',
              borderRadius: 8, color: 'var(--text-primary)', fontSize: '0.9rem',
              boxSizing: 'border-box', outline: 'none', resize: 'vertical',
              fontFamily: 'inherit', lineHeight: 1.6,
              transition: 'border-color 0.2s', marginBottom: 14
            }}
            onFocus={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
            onBlur={e => (e.currentTarget.style.borderColor = 'var(--border-subtle)')}
          />

          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button
              id="regenerate-fix-btn"
              className="btn btn-primary"
              disabled={!instructions.trim() || feedbackMutation.isPending}
              onClick={() => feedbackMutation.mutate(instructions)}
              style={{ display: 'flex', alignItems: 'center', gap: 8 }}
            >
              {feedbackMutation.isPending
                ? <><Loader2 size={14} className="spinner" /> Sending...</>
                : <><Send size={14} /> Regenerate Fix</>
              }
            </button>
          </div>
        </motion.div>
      )}
    </motion.div>
  );
}
