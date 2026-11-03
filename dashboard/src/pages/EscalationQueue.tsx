import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Check, X, ShieldAlert, AlertCircle } from 'lucide-react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../api/client';

const formatTime = (raw: string | null | undefined): string => {
  if (!raw) return '—';
  const d = new Date(raw);
  if (isNaN(d.getTime()) || d.getFullYear() < 2000) return '—';
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  });
};

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

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

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
          ? `Approved ${id.substring(0, 8)} — the job is back at planning. Follow it in the Job Feed.`
          : `Rejected ${id.substring(0, 8)}.`,
      });
    },
    onError: (err: any) => {
      // 409 means the server refused on purpose (already resolved, or the job
      // already has a PR) — show why instead of failing silently.
      setNotice({ kind: 'error', text: err?.response?.data?.error || err?.message || 'Request failed' });
    },
    onSettled: () => {
      // Everything that counts escalations, not just this list: the sidebar
      // badge shares this key; the jobs page reads job states and stats.
      queryClient.invalidateQueries({ queryKey: ['escalations'] });
      queryClient.invalidateQueries({ queryKey: ['job-stats'] });
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
    },
  });

  const handleAction = (id: string, action: Action) => {
    setNotice(null);
    mutation.mutate({ id, action });
  };

  const scored = escalations.filter(hasConfidence);
  const pendingAction = mutation.isPending ? mutation.variables : undefined;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Escalation Queue</h1>
        <p className="page-description">Jobs pending human review due to low confidence or security concerns</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
        <div className="card">
          <div className="stat-label">Pending Review</div>
          <motion.div 
            key={escalations.length} 
            initial={{ scale: 1.2, color: 'var(--text-primary)' }} 
            animate={{ scale: 1, color: 'var(--warning)' }} 
            className="stat-value"
          >
            {escalations.length}
          </motion.div>
        </div>
        <div className="card">
          <div className="stat-label">Avg Confidence</div>
          <div className="stat-value">
            {scored.length > 0
              ? (scored.reduce((s, e) => s + e.confidenceScore, 0) / scored.length).toFixed(2)
              : '—'}
          </div>
        </div>
      </motion.div>

      {notice && (
        <motion.div
          initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
          role={notice.kind === 'error' ? 'alert' : 'status'}
          style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '12px 16px', borderRadius: 8, marginBottom: 16, fontSize: '0.9rem',
            background: notice.kind === 'error' ? 'var(--danger-bg)' : 'var(--success-bg)',
            color: notice.kind === 'error' ? 'var(--danger)' : 'var(--success)',
          }}
        >
          {notice.kind === 'error' ? <AlertCircle size={16} /> : <Check size={16} />}
          {notice.text}
        </motion.div>
      )}

      <motion.div variants={itemVariants} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {isLoading && <p>Loading escalations...</p>}
        {isError && <p>Error connecting to API Gateway.</p>}
        {!isLoading && !isError && (
          <AnimatePresence>
            {escalations.length === 0 && (
              <motion.div 
                initial={{ opacity: 0, scale: 0.95 }} 
                animate={{ opacity: 1, scale: 1 }}
                className="card" 
                style={{ textAlign: 'center', padding: '60px 20px', borderStyle: 'dashed' }}
              >
                <div style={{ display: 'inline-flex', padding: 12, borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success)', marginBottom: 16 }}>
                  <Check size={32} />
                </div>
                <p style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)' }}>Queue is clear</p>
                <p style={{ color: 'var(--text-muted)', marginTop: 4 }}>No escalations pending review</p>
              </motion.div>
            )}
            
            {escalations.map((esc) => (
              <motion.div 
                layout
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, transition: { duration: 0.15 } }}
                className="card" 
                key={esc.id} 
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 24 }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                      <span className="mono" style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{esc.id.substring(0,8)}</span>
                      {hasConfidence(esc) && (
                        <span className="badge badge-warning">Conf: {esc.confidenceScore.toFixed(2)}</span>
                      )}
                      <span className="mono" style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{formatTime(esc.createdAt)}</span>
                    </div>
                    <div style={{ fontWeight: 500, marginBottom: 4, color: 'var(--text-primary)' }}>{esc.job?.repo?.replace('https://github.com/','')}</div>
                    <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: 12 }}>{esc.job?.errorMessage}</div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.8rem', color: 'var(--danger)', fontWeight: 500 }}>
                      <ShieldAlert size={14} /> {esc.reason}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                    <motion.button disabled={mutation.isPending} whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.95 }} className="btn btn-success" onClick={() => handleAction(esc.id, 'approve')}>
                      <Check size={16} /> {pendingAction?.id === esc.id && pendingAction.action === 'approve' ? 'Approving…' : 'Approve'}
                    </motion.button>
                    <motion.button disabled={mutation.isPending} whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.95 }} className="btn btn-outline" onClick={() => handleAction(esc.id, 'reject')} style={{ color: 'var(--danger)', borderColor: 'var(--danger-bg)' }}>
                      <X size={16} /> {pendingAction?.id === esc.id && pendingAction.action === 'reject' ? 'Rejecting…' : 'Reject'}
                    </motion.button>
                  </div>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        )}
      </motion.div>
    </motion.div>
  );
}
