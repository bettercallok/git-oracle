import { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Check, X, ShieldAlert, ShieldCheck, Gauge, WifiOff, Clock } from 'lucide-react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { errorMessage, formatTime, relativeTime, shortRepo } from '../utils/format';
import { PageHeader, Alert, EmptyState, LoadingState } from '../components/ui';

interface Job {
  id: string;
  repo: string;
  errorMessage: string;
}

interface Escalation {
  id: string;
  job: Job;
  reason: string;
  // Null when the escalating agent had no confidence to report (a crash, a
  // rate limit) — not a score of zero.
  confidenceScore: number | null;
  status: string;
  createdAt: string;
}

const fetchEscalations = async (): Promise<Escalation[]> => {
  const { data } = await apiClient.get('/escalations');
  return data;
};

type Action = 'approve' | 'reject';

const resolveEscalation = async ({ id, action }: { id: string; action: Action }) => {
  const { data } = await apiClient.post(`/escalations/${id}/resolve`, { action });
  return data;
};

const hasConfidence = (e: Escalation): e is Escalation & { confidenceScore: number } =>
  typeof e.confidenceScore === 'number';

const confidenceTone = (c: number) => (c >= 0.7 ? 'var(--success)' : c >= 0.4 ? 'var(--warning)' : 'var(--danger)');

export default function EscalationQueue() {
  const queryClient = useQueryClient();

  const { data: escalations = [], isLoading, isError } = useQuery({
    queryKey: ['escalations'],
    queryFn: fetchEscalations,
    refetchInterval: 5000,
  });

  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  // A resolved escalation leaves the list straight away. It used to stay on
  // screen until — and after — the next poll, looking untouched, which is
  // what made the buttons seem broken and invited repeated clicks.
  const mutation = useMutation({
    mutationFn: resolveEscalation,
    onSuccess: (_data, { id, action }) => {
      queryClient.setQueryData<Escalation[]>(['escalations'], (old) => old?.filter((e) => e.id !== id));
      setNotice({
        kind: 'success',
        text: action === 'approve'
          ? `Approved ${id.substring(0, 8)}. The job is back at planning — follow it from Jobs.`
          : `Rejected ${id.substring(0, 8)}. The job won't be retried.`,
      });
    },
    onError: (err) => {
      // 409 means the server refused on purpose (already resolved, or the job
      // already has a PR) — show why instead of failing silently.
      setNotice({ kind: 'error', text: errorMessage(err, 'Request failed') });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['escalations'] });
    },
  });

  const handleAction = (id: string, action: Action) => {
    setNotice(null);
    mutation.mutate({ id, action });
  };

  const scored = escalations.filter(hasConfidence);
  const avgConfidence = scored.length > 0 ? scored.reduce((s, e) => s + e.confidenceScore, 0) / scored.length : null;
  const oldest = escalations.reduce<string | null>((o, e) => (!o || e.createdAt < o ? e.createdAt : o), null);
  const pendingAction = mutation.isPending ? mutation.variables : undefined;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Escalations"
        description="Jobs an agent stopped on — low confidence, a guardrail block, or an error. Approve to send one back through planning, or reject it."
      />

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Waiting for review</span>
            <span className={`stat-icon ${escalations.length ? 'warning' : ''}`}><ShieldAlert size={15} /></span>
          </div>
          <motion.div key={escalations.length} initial={{ scale: 1.08 }} animate={{ scale: 1 }} className="stat-value" style={{ transformOrigin: 'left' }}>
            {escalations.length}
          </motion.div>
        </div>
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Average confidence</span>
            <span className="stat-icon"><Gauge size={15} /></span>
          </div>
          <div className="stat-value">{avgConfidence === null ? '—' : avgConfidence.toFixed(2)}</div>
          <div className="stat-subtitle">
            {scored.length === 0 ? 'No scored escalations' : `Across ${scored.length} with a score`}
          </div>
        </div>
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Oldest waiting</span>
            <span className="stat-icon"><Clock size={15} /></span>
          </div>
          <div className="stat-value">{oldest ? relativeTime(oldest).replace(' ago', '') : '—'}</div>
          <div className="stat-subtitle">{oldest ? formatTime(oldest) : 'Queue is empty'}</div>
        </div>
      </motion.div>

      <AnimatePresence>
        {notice && (
          <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginBottom: 16 }}>
            <Alert tone={notice.kind === 'error' ? 'danger' : 'success'}
                   action={<button className="btn btn-ghost btn-sm btn-icon" onClick={() => setNotice(null)} aria-label="Dismiss"><X size={14} /></button>}>
              {notice.text}
            </Alert>
          </motion.div>
        )}
      </AnimatePresence>

      <motion.div variants={itemVariants} className="stack">
        {isLoading && <div className="card flush"><LoadingState label="Loading escalations…" /></div>}
        {isError && (
          <div className="card">
            <EmptyState icon={<WifiOff size={20} />} title="Can't reach the API gateway" description="Retrying every five seconds." />
          </div>
        )}
        {!isLoading && !isError && (
          <AnimatePresence initial={false}>
            {escalations.length === 0 && (
              <motion.div key="empty" initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} className="card dashed">
                <EmptyState
                  icon={<ShieldCheck size={20} />}
                  tone="success"
                  title="Nothing to review"
                  description="When an agent isn't confident enough to continue, the job lands here for a human decision."
                />
              </motion.div>
            )}

            {escalations.map((esc) => {
              const busy = pendingAction?.id === esc.id;
              return (
                <motion.div
                  layout
                  key={esc.id}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, x: 16, transition: { duration: 0.16 } }}
                  className="card"
                  style={{ padding: 0, overflow: 'hidden' }}
                >
                  <div style={{ display: 'flex', gap: 20, padding: '18px 20px', alignItems: 'flex-start', flexWrap: 'wrap' }}>
                    <div style={{ flex: 1, minWidth: 260 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6, flexWrap: 'wrap' }}>
                        <span className="cell-primary" style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
                          {shortRepo(esc.job?.repo) || 'Unknown repository'}
                        </span>
                        {esc.job?.id && (
                          <Link to={`/job/${esc.job.id}`} className="mono link" style={{ fontSize: '0.8rem' }}>
                            job {esc.job.id.substring(0, 8)}
                          </Link>
                        )}
                        <span className="muted" style={{ fontSize: '0.8rem' }} title={formatTime(esc.createdAt)}>
                          · {relativeTime(esc.createdAt)}
                        </span>
                      </div>
                      {esc.job?.errorMessage && (
                        <div style={{ fontSize: '0.88rem', color: 'var(--text-secondary)', marginBottom: 12, lineHeight: 1.55 }}>
                          {esc.job.errorMessage}
                        </div>
                      )}
                      <div className="alert alert-warning" style={{ padding: '9px 12px', fontSize: '0.86rem' }}>
                        <ShieldAlert size={14} />
                        <div className="alert-content"><span style={{ color: 'var(--text-primary)' }}>{esc.reason}</span></div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 12, flexShrink: 0 }}>
                      {hasConfidence(esc) ? (
                        <div style={{ width: 150 }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.76rem', marginBottom: 5 }}>
                            <span className="muted">Confidence</span>
                            <span className="mono" style={{ color: confidenceTone(esc.confidenceScore) }}>{esc.confidenceScore.toFixed(2)}</span>
                          </div>
                          <div className="meter">
                            <div style={{ width: `${Math.max(0, Math.min(1, esc.confidenceScore)) * 100}%`, background: confidenceTone(esc.confidenceScore) }} />
                          </div>
                        </div>
                      ) : (
                        <span className="muted" style={{ fontSize: '0.76rem' }}>No confidence reported</span>
                      )}
                      <div style={{ display: 'flex', gap: 8 }}>
                        <button disabled={mutation.isPending} className="btn btn-danger btn-sm" onClick={() => handleAction(esc.id, 'reject')}>
                          <X size={14} /> {busy && pendingAction.action === 'reject' ? 'Rejecting…' : 'Reject'}
                        </button>
                        <button disabled={mutation.isPending} className="btn btn-success btn-sm" onClick={() => handleAction(esc.id, 'approve')}>
                          <Check size={14} /> {busy && pendingAction.action === 'approve' ? 'Approving…' : 'Approve'}
                        </button>
                      </div>
                    </div>
                  </div>
                </motion.div>
              );
            })}
          </AnimatePresence>
        )}
      </motion.div>
    </motion.div>
  );
}
