import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, CheckCircle2, Loader2, AlertCircle, GitPullRequest, RefreshCw, Send, ExternalLink } from 'lucide-react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import { stateMeta, isRunning } from '../utils/jobState';

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
  state: string;
  /** Why it's in this state — the escalation or failure reason. */
  stateReason?: string | null;
  stateChangedAt?: string | null;
  attempts?: number | null;
  // The entity field is tokenBudgetUsed; this page read `tokensUsed`, which the
  // API never sends, so it always showed 0.
  tokenBudgetUsed?: number | null;
  rootCommit?: string | null;
  causalScore?: number | null;
  baseCommit?: string | null;
  createdAt: string;
  prUrl?: string;
}

interface JobEvent {
  id: string;
  type: 'STATE_CHANGE' | 'AGENT_PROGRESS' | 'ERROR';
  fromState?: string | null;
  toState?: string | null;
  actor: string;
  message?: string | null;
  createdAt: string;
}

const fetchJob = async (id: string): Promise<Job> => {
  const { data } = await apiClient.get(`/jobs/${id}`);
  return data;
};

const fetchEvents = async (id: string): Promise<JobEvent[]> => {
  const { data } = await apiClient.get(`/jobs/${id}/events`);
  return data;
};

// Every LLM call in a job is traced under the job id (structured_output.py).
const LANGFUSE_URL = import.meta.env.VITE_LANGFUSE_URL || 'http://localhost:3000';

/** "2m 14s" between two timestamps; empty when either is missing. */
const duration = (from: string, to?: string | null): string => {
  if (!to) return '';
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (!(ms >= 0)) return '';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`;
};

const shortSha = (sha?: string | null) => (sha ? sha.substring(0, 8) : '');

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

  const { data: events = [] } = useQuery({
    queryKey: ['job-events', jobId],
    queryFn: () => fetchEvents(jobId!),
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
      queryClient.invalidateQueries({ queryKey: ['job-events', jobId] });
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

  const status = job.state;
  const meta = stateMeta(status);
  const isSuccess = meta.group === 'succeeded';
  const needsHuman = meta.group === 'attention' || meta.group === 'failed';
  // The timeline's last state change, to show how long the current state has run.
  const stateChanges = events.filter(e => e.type === 'STATE_CHANGE');

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 8 }}>
          <Link to="/" className="btn btn-outline" style={{ padding: '6px 10px', textDecoration: 'none' }}>
            <ArrowLeft size={16} />
          </Link>
          <h1 className="page-title" style={{ marginBottom: 0 }}>Job {job.id.substring(0,8)}</h1>
          <span className={`badge ${meta.badge}`} style={{ padding: '4px 8px' }}>
            {isSuccess ? <CheckCircle2 size={12} style={{ marginRight: 2 }} />
              : needsHuman ? <AlertCircle size={12} style={{ marginRight: 2 }} />
              : <Loader2 size={12} className={isRunning(status) ? 'spinner' : undefined} style={{ marginRight: 2 }} />}
            {status}
          </span>
          <a href={`${LANGFUSE_URL}/trace/${job.id}`} target="_blank" rel="noreferrer"
             className="btn btn-outline" style={{ marginLeft: 'auto', padding: '6px 10px', textDecoration: 'none' }}>
            View LLM trace <ExternalLink size={14} />
          </a>
        </div>
        <p className="page-description" style={{ marginLeft: 50 }}>
          {meta.label}{job.stateChangedAt && !meta.terminal ? ` for ${duration(job.stateChangedAt, new Date().toISOString())}` : ''}
        </p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Repository</div>
          <div style={{ fontSize: '1.1rem', fontWeight: 500, color: 'var(--text-primary)' }}>{job.repo.replace('https://github.com/','')}</div>
        </div>
        <div className="card">
          <div className="stat-label">Tokens Used</div>
          <div className="mono" style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>{job.tokenBudgetUsed ?? 0}</div>
        </div>
        <div className="card">
          <div className="stat-label">Fix Attempts</div>
          <div className="stat-value">{job.attempts ?? 0}</div>
        </div>
        <div className="card">
          <div className="stat-label">Root Cause</div>
          <div className="mono" style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>
            {job.rootCommit
              ? `${shortSha(job.rootCommit)}${job.causalScore != null ? ` (${job.causalScore.toFixed(2)})` : ''}`
              : 'not identified'}
          </div>
        </div>
        <div className="card">
          <div className="stat-label">When</div>
          <div className="stat-value" style={{ fontSize: '0.9rem' }}>{formatTime(job.createdAt)}</div>
        </div>
      </motion.div>

      {/* Why it stopped */}
      {needsHuman && job.stateReason && (
        <motion.div variants={itemVariants}
          style={{
            display: 'flex', alignItems: 'flex-start', gap: 12,
            padding: '14px 20px', borderRadius: 10, marginBottom: 16,
            background: 'var(--danger-bg)', border: '1px solid var(--danger)'
          }}
        >
          <AlertCircle size={18} color="var(--danger)" style={{ flexShrink: 0, marginTop: 2 }} />
          <div>
            <div style={{ color: 'var(--text-primary)', fontWeight: 500, marginBottom: 4 }}>{meta.label}</div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {job.stateReason}
            </div>
          </div>
        </motion.div>
      )}

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
        {events.length === 0 ? (
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            No events recorded yet. Jobs created before the timeline existed have none.
          </p>
        ) : (
          <div className="timeline">
            {events.map((e, i) => {
              const isState = e.type === 'STATE_CHANGE';
              const toMeta = stateMeta(e.toState);
              const next = isState ? stateChanges[stateChanges.indexOf(e) + 1] : undefined;
              const spent = isState
                ? duration(e.createdAt, next?.createdAt ?? (toMeta.terminal ? null : new Date().toISOString()))
                : '';
              const dot = e.type === 'ERROR' ? 'var(--danger)' : isState ? toMeta.color : 'var(--border-subtle)';
              return (
                <motion.div key={e.id} className="timeline-item"
                  initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: Math.min(i, 10) * 0.03 }}>
                  <div className="timeline-dot" style={{ borderColor: dot }} />
                  <div className="timeline-time">
                    {formatTime(e.createdAt)} · {e.actor}{spent ? ` · ${spent}` : ''}
                  </div>
                  <div className="timeline-title" style={isState ? undefined : { fontWeight: 400, fontSize: '0.9rem' }}>
                    {isState ? toMeta.label : e.type === 'ERROR' ? 'Error' : 'Progress'}
                  </div>
                  {e.message && <div className="timeline-body" style={{ wordBreak: 'break-word' }}>{e.message}</div>}
                </motion.div>
              );
            })}
          </div>
        )}
      </motion.div>

      {/* Human Feedback Panel */}
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
    </motion.div>
  );
}
