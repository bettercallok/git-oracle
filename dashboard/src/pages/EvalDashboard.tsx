import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { Target, ListChecks, GitMerge, TrendingDown, LineChart, MessagesSquare, FileText, WifiOff, TestTubes } from 'lucide-react';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { useNumberTicker } from '../utils/useNumberTicker';
import { PageHeader, EmptyState, LoadingState } from '../components/ui';

interface PromptVersion {
  agent: string;
  version: number;
  jobs: number;
  successes: number;
  /** null when the version has no finished jobs yet — rendered as "—". */
  accuracy: number | null;
  /** null when no finished job on this version recorded token usage. */
  avgTokens: number | null;
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

/** Measured per-version performance, joined server-side from the jobs that
 *  actually ran on each prompt revision. */
const fetchPrompts = async (): Promise<PromptVersion[]> => {
  const { data } = await apiClient.get('/prompts/stats');
  return data;
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
  { key: 'CLOSED',   label: 'Closed',   color: 'var(--text-muted)' },
  { key: 'REVERTED', label: 'Reverted', color: 'var(--danger)' },
];

const RECENT_WINDOW = 8;

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

  const latest = evals[0];
  const accuracy = useNumberTicker(latest ? Math.round(latest.accuracy * 100) : 0);
  const recentRuns = evals.slice(0, RECENT_WINDOW);
  // Both of these were once hardcoded (50 cases, 92% merge rate). Eval cases is
  // the count the latest run actually covered; regressions come from the history.
  const cases = useNumberTicker(latest ? (latest.casesTotal ?? 0) : 0);
  const regressions = countRegressions(recentRuns);
  const delta = evals.length > 1 ? (evals[0].accuracy - evals[1].accuracy) * 100 : null;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Evaluations"
        description="How well GitOracle finds root causes and writes fixes, measured against a golden dataset — plus what happened to the pull requests it opened."
      />

      {evalsLoading && <LoadingState label="Loading evaluations…" />}
      {evalsError && (
        <div className="card">
          <EmptyState icon={<WifiOff size={20} />} title="Couldn't load evaluations" description="The orchestrator didn't answer. Retrying every ten seconds." />
        </div>
      )}

      {!evalsLoading && !evalsError && (
        <>
          <motion.div variants={itemVariants} className="stat-grid">
            <div className="card">
              <div className="stat-head">
                <span className="stat-label">Latest accuracy</span>
                <span className="stat-icon accent"><Target size={15} /></span>
              </div>
              <div className="stat-value">{latest ? `${accuracy}%` : '—'}</div>
              <div className="stat-subtitle">
                {delta !== null
                  ? <span style={{ color: delta >= 0 ? 'var(--success)' : 'var(--danger)' }}>{delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)} pts vs previous run</span>
                  : latest ? <span className="mono">{latest.goldenDatasetVersion}</span> : 'No runs yet'}
              </div>
            </div>
            <div className="card">
              <div className="stat-head">
                <span className="stat-label">Eval cases</span>
                <span className="stat-icon"><ListChecks size={15} /></span>
              </div>
              <div className="stat-value">{latest ? cases : '—'}</div>
              <div className="stat-subtitle">{latest ? 'In the latest run' : 'Run eval/run_evals.py'}</div>
            </div>
            <div className="card">
              <div className="stat-head">
                <span className="stat-label">PR merge rate</span>
                <span className="stat-icon success"><GitMerge size={15} /></span>
              </div>
              <div className="stat-value">
                {prStats && prStats.mergeRate !== null ? `${Math.round(prStats.mergeRate * 100)}%` : '—'}
              </div>
              <div className="stat-subtitle">
                {prStats && prStats.total > 0
                  ? `Across ${prStats.total} pull request${prStats.total === 1 ? '' : 's'}`
                  : 'No pull request outcomes yet'}
              </div>
            </div>
            <div className="card">
              <div className="stat-head">
                <span className="stat-label">Regressions</span>
                <span className={`stat-icon ${regressions > 0 ? 'danger' : ''}`}><TrendingDown size={15} /></span>
              </div>
              <div className="stat-value" style={{ color: regressions > 0 ? 'var(--danger)' : undefined }}>{regressions}</div>
              <div className="stat-subtitle">
                {recentRuns.length > 1 ? `Runs that scored lower than the one before, last ${recentRuns.length}` : 'Needs at least two runs'}
              </div>
            </div>
          </motion.div>

          <motion.div variants={itemVariants} className="grid-2" style={{ marginBottom: 16 }}>
            <div className="card">
              <div className="card-header">
                <div>
                  <div className="card-title"><LineChart size={16} /> Accuracy by run</div>
                  <div className="card-subtitle">Last {Math.min(RECENT_WINDOW, evals.length) || RECENT_WINDOW} runs, oldest first · dashed line is the 75% CI gate</div>
                </div>
              </div>
              {evals.length === 0 ? (
                <EmptyState icon={<TestTubes size={20} />} title="No eval runs yet"
                            description={<>Run <code className="inline">python eval/run_evals.py</code> with the services up.</>} />
              ) : (
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, height: 180, paddingTop: 22, position: 'relative' }}>
                  {/* 75% gate — the CI regression threshold */}
                  <div style={{ position: 'absolute', left: 0, right: 0, bottom: `calc(${0.75 * 140}px + 22px)`, borderTop: '1px dashed var(--border-strong)' }} />
                  {[...recentRuns].reverse().map((run, i, arr) => {
                    const isLatest = i === arr.length - 1;
                    return (
                      <div key={run.id} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, minWidth: 0 }}
                           title={`${run.goldenDatasetVersion} · ${run.casesTotal} cases · ${(run.avgLatencyMs / 1000).toFixed(1)}s avg`}>
                        <div style={{ position: 'relative', width: '100%', maxWidth: 34, height: 140, display: 'flex', alignItems: 'flex-end' }}>
                          <motion.div
                            initial={{ height: 0 }}
                            animate={{ height: `${run.accuracy * 100}%` }}
                            transition={{ delay: 0.1 + i * 0.04, type: 'spring', stiffness: 200, damping: 24 }}
                            style={{
                              width: '100%', borderRadius: '5px 5px 2px 2px',
                              background: isLatest ? 'var(--accent)' : 'color-mix(in srgb, var(--accent) 35%, transparent)',
                            }}
                          />
                          <div className="mono" style={{ position: 'absolute', top: -18, left: '50%', transform: 'translateX(-50%)', fontSize: '0.7rem', color: isLatest ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                            {(run.accuracy * 100).toFixed(0)}
                          </div>
                        </div>
                        <div className="mono muted truncate" style={{ fontSize: '0.68rem', maxWidth: '100%' }}>
                          {new Date(run.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <div className="card">
              <div className="card-header">
                <div>
                  <div className="card-title"><MessagesSquare size={16} /> Pull request outcomes</div>
                  <div className="card-subtitle">What reviewers did with GitOracle's pull requests</div>
                </div>
              </div>
              {(!prStats || prStats.total === 0) ? (
                <EmptyState icon={<GitMerge size={20} />} title="No outcomes yet"
                            description={<>Merge or close a GitOracle pull request — the GitHub <code className="inline">pull_request</code> webhook fills this in.</>} />
              ) : (
                <div className="stack-lg" style={{ paddingTop: 4 }}>
                  {FEEDBACK_ROWS.map((item, i) => {
                    const count = prStats.counts[item.key] ?? 0;
                    const pct = Math.round((count / prStats.total) * 100);
                    return (
                      <div key={item.label}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 7, fontSize: '0.88rem' }}>
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, color: 'var(--text-secondary)' }}>
                            <i style={{ width: 8, height: 8, borderRadius: 2, background: item.color, display: 'block' }} /> {item.label}
                          </span>
                          <span className="mono tabular" style={{ color: 'var(--text-primary)' }}>
                            {count} <span className="muted">· {pct}%</span>
                          </span>
                        </div>
                        <div className="meter">
                          <motion.div initial={{ width: 0 }} animate={{ width: `${pct}%` }}
                                      transition={{ delay: 0.2 + i * 0.08, duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
                                      style={{ background: item.color }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </motion.div>

          <motion.div variants={itemVariants} className="table-container">
            <div className="card-header" style={{ margin: 0, padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
              <div>
                <div className="card-title"><FileText size={16} /> Prompt versions</div>
                <div className="card-subtitle">Measured from the jobs that actually ran on each version</div>
              </div>
            </div>
            {prompts.length === 0 ? (
              <EmptyState icon={<FileText size={20} />} title="No measured prompt versions yet"
                          description="Finish a job and its prompt versions show up here with real outcomes." />
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Agent</th>
                      <th>Version</th>
                      <th className="num">Accuracy</th>
                      <th className="num">Succeeded</th>
                      <th className="num">Avg tokens</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {prompts.map((p) => (
                      <tr key={`${p.agent}-${p.version}`}>
                        <td className="cell-primary" style={{ textTransform: 'capitalize' }}>{p.agent}</td>
                        <td className="mono">v{p.version}</td>
                        <td className="num mono tabular" style={{
                          color: p.accuracy === null ? 'var(--text-muted)' : p.accuracy >= 0.9 ? 'var(--success)' : p.accuracy >= 0.75 ? 'var(--text-primary)' : 'var(--warning)',
                        }}>
                          {p.accuracy === null ? '—' : `${(p.accuracy * 100).toFixed(0)}%`}
                        </td>
                        <td className="num mono tabular muted">{p.successes} / {p.jobs}</td>
                        <td className="num mono tabular">{p.avgTokens === null ? '—' : p.avgTokens.toLocaleString()}</td>
                        <td>
                          {p.active
                            ? <span className="badge badge-success">Active</span>
                            : <span className="badge badge-neutral no-dot">Archived</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </motion.div>
        </>
      )}
    </motion.div>
  );
}
