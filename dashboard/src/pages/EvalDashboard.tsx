import { motion } from 'framer-motion';
import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import { Loader2, AlertCircle } from 'lucide-react';

// --- Custom Number Ticker Hook ---
function useNumberTicker(target: number, duration: number = 800) {
  const [value, setValue] = useState(0);
  useEffect(() => {
    let startTimestamp: number | null = null;
    const step = (timestamp: number) => {
      if (!startTimestamp) startTimestamp = timestamp;
      const progress = Math.min((timestamp - startTimestamp) / duration, 1);
      const easeProgress = progress === 1 ? 1 : 1 - Math.pow(2, -10 * progress);
      setValue(Math.floor(easeProgress * target));
      if (progress < 1) window.requestAnimationFrame(step);
    };
    window.requestAnimationFrame(step);
  }, [target, duration]);
  return value;
}

interface PromptVersion {
  agent: string;
  version: string;
  accuracy: number;
  avgTokens: number;
  active: boolean;
}

interface EvalRun {
  id: string;
  goldenDatasetVersion: string;
  accuracy: number;
  avgLatencyMs: number;
  casesTotal: number;
  createdAt: string;
}

/** Counts runs that scored worse than the run immediately before them.
 *  `runs` arrives newest-first, so compare each entry against its successor. */
function countRegressions(runs: EvalRun[]): number {
  let regressions = 0;
  for (let i = 0; i < runs.length - 1; i++) {
    if (runs[i].accuracy < runs[i + 1].accuracy) regressions++;
  }
  return regressions;
}

const fetchEvals = async (): Promise<EvalRun[]> => {
  const { data } = await apiClient.get('/evals');
  return data;
};

// Simulated fetch for prompts since we don't have a prompt registry DB yet
const fetchPrompts = async (): Promise<PromptVersion[]> => {
  return [
    { agent: 'Planner', version: 'v3.2', accuracy: 0.94, avgTokens: 1850, active: true },
    { agent: 'Planner', version: 'v3.1', accuracy: 0.91, avgTokens: 2100, active: false },
    { agent: 'Planner', version: 'v3.0', accuracy: 0.87, avgTokens: 2400, active: false },
    { agent: 'Fixer', version: 'v2.5', accuracy: 0.89, avgTokens: 1200, active: true },
    { agent: 'Fixer', version: 'v2.4', accuracy: 0.85, avgTokens: 1450, active: false },
    { agent: 'Fixer', version: 'v2.3', accuracy: 0.82, avgTokens: 1600, active: false },
  ];
};

interface PrOutcomeStats {
  counts: Record<string, number>;
  total: number;
  mergeRate: number | null;
}

const fetchPrOutcomes = async (): Promise<PrOutcomeStats> => {
  const { data } = await apiClient.get('/pr-outcomes/stats');
  return data;
};

// Backed by the pr_outcomes table, populated from real GitHub pull_request /
// pull_request_review webhooks. Order matches the outcome ranking used server-side.
const FEEDBACK_ROWS: { key: string; label: string; color: string }[] = [
  { key: 'MERGED',   label: 'Merged',   color: 'var(--success)' },
  { key: 'APPROVED', label: 'Approved', color: 'var(--info)' },
  { key: 'CLOSED',   label: 'Closed',   color: 'var(--warning)' },
  { key: 'REVERTED', label: 'Reverted', color: 'var(--danger)' },
];

const PLACEHOLDER_BADGE = (
  <span
    className="badge"
    style={{ background: 'var(--border-strong)', color: 'var(--text-muted)', fontSize: '0.65rem', marginLeft: 8 }}
    title="Illustrative only — prompt performance is not measured per version yet"
  >
    SAMPLE DATA
  </span>
);

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function EvalDashboard() {
  const { data: evals = [], isLoading: evalsLoading, isError: evalsError } = useQuery({
    queryKey: ['evals'],
    queryFn: fetchEvals,
    refetchInterval: 10000,
  });

  const { data: prompts = [] } = useQuery({
    queryKey: ['prompts'],
    queryFn: fetchPrompts,
  });

  const { data: prStats } = useQuery({
    queryKey: ['pr-outcomes'],
    queryFn: fetchPrOutcomes,
    refetchInterval: 10000,
  });

  const latestAccuracy = evals.length > 0 ? evals[0].accuracy * 100 : 0;
  const accuracy = useNumberTicker(latestAccuracy, 1000);

  // Both of these were hardcoded (50 cases, 92% merge rate) and bore no relation
  // to anything the system had actually measured. Eval Cases now reports the case
  // count the most recent run genuinely covered; regressions are derived from the
  // accuracy history rather than asserted.
  const RECENT_WINDOW = 6;
  const recentRuns = evals.slice(0, RECENT_WINDOW);
  const cases = useNumberTicker(evals.length > 0 ? (evals[0].casesTotal ?? 0) : 0, 1000);
  const regressions = countRegressions(recentRuns);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Eval Dashboard</h1>
        <p className="page-description">Prompt performance, feedback outcomes, and accuracy trends</p>
      </motion.div>

      {evalsLoading && <p><Loader2 className="spinner" size={16} /> Loading evaluations...</p>}
      {evalsError && <p style={{ color: 'var(--danger)' }}><AlertCircle size={16} /> Error loading evaluations.</p>}
      
      {!evalsLoading && !evalsError && (
        <>
          <motion.div variants={itemVariants} className="stat-grid">
            <div className="card">
              <div className="stat-label">Latest Accuracy</div>
              <div className="stat-value">{accuracy}%</div>
              <div className="stat-subtitle mono" style={{ color: 'var(--text-muted)' }}>{evals.length > 0 ? evals[0].goldenDatasetVersion : 'No Data'}</div>
            </div>
            <div className="card">
              <div className="stat-label">Eval Cases</div>
              <div className="stat-value">{cases}</div>
              <div className="stat-subtitle">in latest run</div>
            </div>
            <div className="card">
              <div className="stat-label">PR Merge Rate</div>
              <div className="stat-value" style={{ color: 'var(--success)' }}>
                {prStats && prStats.mergeRate !== null
                  ? `${Math.round(prStats.mergeRate * 100)}%`
                  : '—'}
              </div>
              <div className="stat-subtitle">
                {prStats && prStats.total > 0
                  ? `across ${prStats.total} PR${prStats.total === 1 ? '' : 's'}`
                  : 'no PR outcomes yet'}
              </div>
            </div>
            <div className="card">
              <div className="stat-label">Regressions</div>
              <div className="stat-value" style={{ color: regressions > 0 ? 'var(--danger)' : 'var(--text-primary)' }}>
                {regressions}
              </div>
              <div className="stat-subtitle">
                {recentRuns.length > 1 ? `in last ${recentRuns.length} runs` : 'need 2+ runs'}
              </div>
            </div>
          </motion.div>

          <motion.div variants={itemVariants} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 24 }}>
            <div className="card">
              <h2 style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--text-primary)', marginBottom: 20 }}>Accuracy Trend</h2>
              <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, height: 160 }}>
                {evals.slice(0, 6).reverse().map((run, i) => (
                  <div key={run.id} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                    <div style={{ position: 'relative', width: '100%', maxWidth: 36, height: 130, display: 'flex', alignItems: 'flex-end' }}>
                      <motion.div
                        initial={{ height: 0 }}
                        animate={{ height: `${run.accuracy * 100}%` }}
                        transition={{ delay: 0.2 + (i * 0.05), type: 'spring' as const }}
                        style={{ width: '100%', background: 'var(--accent)', borderRadius: '3px 3px 0 0' }}
                      />
                      <div className="mono" style={{ position: 'absolute', top: -20, left: '50%', transform: 'translateX(-50%)', fontSize: '0.7rem', color: 'var(--text-secondary)' }}>
                        {(run.accuracy * 100).toFixed(0)}%
                      </div>
                    </div>
                    <div className="mono" style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{new Date(run.createdAt).toLocaleDateString(undefined, {month: 'short', day: 'numeric'})}</div>
                  </div>
                ))}
              </div>
            </div>

            <div className="card">
              <h2 style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--text-primary)', marginBottom: 20 }}>
                PR Feedback
              </h2>
              {(!prStats || prStats.total === 0) && (
                <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', paddingTop: 6 }}>
                  No PR outcomes recorded yet. Merge or close a GitOracle PR — the
                  GitHub <span className="mono">pull_request</span> webhook populates this.
                </p>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 18, paddingTop: 6 }}>
                {prStats && prStats.total > 0 && FEEDBACK_ROWS.map((item, i) => {
                  const count = prStats.counts[item.key] ?? 0;
                  const pct = Math.round((count / prStats.total) * 100);
                  return (
                  <div key={item.label}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                      <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{item.label}</span>
                      <span className="mono" style={{ fontSize: '0.85rem', color: 'var(--text-primary)' }}>
                        {count} ({pct}%)
                      </span>
                    </div>
                    <div style={{ height: 6, background: 'var(--border-strong)', borderRadius: 3, overflow: 'hidden' }}>
                      <motion.div
                        initial={{ width: 0 }}
                        animate={{ width: `${pct}%` }}
                        transition={{ delay: 0.3 + (i * 0.1), duration: 0.8, ease: 'easeOut' }}
                        style={{ height: '100%', background: item.color, borderRadius: 3 }}
                      />
                    </div>
                  </div>
                  );
                })}
              </div>
            </div>
          </motion.div>

          <motion.div variants={itemVariants} className="table-container">
            <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border-subtle)' }}>
              <h2 style={{ fontSize: '1rem', fontWeight: 500, color: 'var(--text-primary)' }}>
                Prompt Versions{PLACEHOLDER_BADGE}
              </h2>
            </div>
            <table className="table">
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Version</th>
                  <th>Accuracy</th>
                  <th>Avg Tokens</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {prompts.map((p) => (
                  <tr key={`${p.agent}-${p.version}`}>
                    <td style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{p.agent}</td>
                    <td className="mono">{p.version}</td>
                    <td className="mono" style={{ color: p.accuracy >= 0.9 ? 'var(--success)' : 'var(--warning)' }}>
                      {(p.accuracy * 100).toFixed(0)}%
                    </td>
                    <td className="mono">{p.avgTokens.toLocaleString()}</td>
                    <td>
                      {p.active
                        ? <span className="badge badge-success">ACTIVE</span>
                        : <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Archived</span>
                      }
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </motion.div>
        </>
      )}
    </motion.div>
  );
}
