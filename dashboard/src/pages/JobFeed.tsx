import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { Activity, CheckCircle2, AlertTriangle, Layers, Search, Wand2, Inbox, WifiOff, XCircle } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { stateMeta, isRunning, isSucceeded, GROUPS, type StateGroup } from '../utils/jobState';
import { containerVariants, itemVariants } from '../utils/motion';
import { formatNumber, formatTime, relativeTime, shortRepo } from '../utils/format';
import { useNumberTicker } from '../utils/useNumberTicker';
import { PageHeader, EmptyState, LoadingState } from '../components/ui';

// Field names as the API sends them (the AgentJob entity). This read
// `tokensUsed` and `errorMessage`, which don't exist, so the Tokens column was
// always "—" and every row said "No description".
interface Job {
  id: string;
  repo: string;
  errorId?: string | null;
  stateReason?: string | null;
  state: string; // see utils/jobState.ts
  createdAt: string;
  tokenBudgetUsed?: number | null;
}

/** Counts over ALL the tenant's jobs; the list below is only the latest 50. */
interface JobStats {
  total: number;
  byState: Record<string, number>;
  /** Exactly what the escalation queue lists. */
  escalationsPending: number;
}

const fetchJobs = async (): Promise<Job[]> => {
  const { data } = await apiClient.get('/jobs');
  return data;
};

const fetchStats = async (): Promise<JobStats> => {
  const { data } = await apiClient.get('/jobs/stats');
  return data;
};

type Filter = 'all' | 'running' | 'succeeded' | 'escalated' | 'failed';

// "Needs attention" was escalated + failed, under a link to the escalation
// queue — which only ever lists escalated jobs, so the two never agreed.
const FILTERS: { key: Filter; label: string; test: (s: string) => boolean }[] = [
  { key: 'all', label: 'All', test: () => true },
  { key: 'running', label: 'Running', test: isRunning },
  { key: 'succeeded', label: 'PR opened', test: isSucceeded },
  { key: 'escalated', label: 'Escalated', test: s => s === 'ESCALATED' },
  { key: 'failed', label: 'Failed', test: s => s === 'FAILED' },
];

const sumStates = (byState: Record<string, number>, test: (s: string) => boolean) =>
  Object.entries(byState).reduce((n, [state, c]) => (test(state) ? n + c : n), 0);

/** What the row says about the job: why it is in its state, else where it came from. */
function describe(job: Job): string {
  return job.stateReason || job.errorId || '';
}

function StatusBadge({ state }: { state: string }) {
  const meta = stateMeta(state);
  return <span className={`badge ${meta.badge} ${meta.group === 'running' ? 'live' : ''}`}>{meta.label}</span>;
}

export default function JobFeed() {
  const { canWrite } = useAuth();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');

  const { data: jobs = [], isLoading, isError } = useQuery({
    queryKey: ['jobs'],
    queryFn: fetchJobs,
    refetchInterval: 2000, // live poll
  });

  const { data: stats } = useQuery({
    queryKey: ['job-stats'],
    queryFn: fetchStats,
    refetchInterval: 2000,
  });

  const counts = useMemo(() => {
    // Prefer the server's counts over every job; fall back to the loaded list
    // only until they arrive.
    const byState: Record<string, number> = stats?.byState ?? jobs.reduce<Record<string, number>>(
      (m, j) => ({ ...m, [j.state]: (m[j.state] ?? 0) + 1 }), {});
    const byGroup = {} as Record<StateGroup, number>;
    for (const [state, n] of Object.entries(byState)) {
      const g = stateMeta(state).group;
      byGroup[g] = (byGroup[g] ?? 0) + n;
    }
    return {
      byGroup,
      total: stats?.total ?? jobs.length,
      running: sumStates(byState, isRunning),
      succeeded: sumStates(byState, isSucceeded),
      escalated: stats?.escalationsPending ?? (byState.ESCALATED ?? 0),
      failed: byState.FAILED ?? 0,
    };
  }, [stats, jobs]);

  const total = useNumberTicker(counts.total);
  const running = useNumberTicker(counts.running);
  const succeeded = useNumberTicker(counts.succeeded);
  const escalated = useNumberTicker(counts.escalated);

  // Escalated jobs aren't finished — a human may still send them back through.
  const finished = counts.succeeded + (counts.byGroup.failed ?? 0) + (counts.byGroup.closed ?? 0);
  const successRate = finished > 0 ? Math.round((counts.succeeded / finished) * 100) : null;

  const visible = useMemo(() => {
    const f = FILTERS.find(x => x.key === filter)!;
    const q = query.trim().toLowerCase();
    return jobs.filter(j =>
      f.test(j.state) &&
      (!q || j.id.includes(q) || shortRepo(j.repo).toLowerCase().includes(q) || describe(j).toLowerCase().includes(q)),
    );
  }, [jobs, filter, query]);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Jobs"
        description="Every bug GitOracle is working on, from first report to pull request. Updates live."
        actions={canWrite ? <Link to="/fix" className="btn btn-primary"><Wand2 size={15} /> Ask to fix</Link> : undefined}
      />

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Total jobs</span>
            <span className="stat-icon"><Layers size={15} /></span>
          </div>
          <div className="stat-value">{total}</div>
          {counts.total > 0 ? (
            <>
              <div className="dist-bar" style={{ marginTop: 14 }} aria-label="Jobs by state">
                {GROUPS.filter(g => counts.byGroup[g.group]).map(g => (
                  <span key={g.group} title={`${g.label}: ${counts.byGroup[g.group]}`}
                        style={{ flexGrow: counts.byGroup[g.group], background: g.color }} />
                ))}
              </div>
            </>
          ) : (
            <div className="stat-subtitle">No jobs yet</div>
          )}
        </div>
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Running now</span>
            <span className="stat-icon accent"><Activity size={15} /></span>
          </div>
          <div className="stat-value">{running}</div>
          <div className="stat-subtitle">Investigating, planning, fixing or testing</div>
        </div>
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Pull requests opened</span>
            <span className="stat-icon success"><CheckCircle2 size={15} /></span>
          </div>
          <div className="stat-value">{succeeded}</div>
          <div className="stat-subtitle">
            {successRate === null ? 'No finished jobs yet' : `${successRate}% of finished jobs`}
          </div>
        </div>
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Waiting on you</span>
            <span className={`stat-icon ${counts.escalated > 0 ? 'warning' : ''}`}><AlertTriangle size={15} /></span>
          </div>
          <div className="stat-value">{escalated}</div>
          <div className="stat-subtitle" style={{ display: 'flex', flexWrap: 'wrap', gap: '2px 10px', alignItems: 'center' }}>
            {counts.escalated > 0
              ? <Link to="/escalations" className="link">{counts.escalated === 1 ? '1 escalation' : `${counts.escalated} escalations`} to review →</Link>
              : 'No escalations to review'}
            {counts.failed > 0 && (
              <button className="link" style={{ background: 'none', border: 0, padding: 0, font: 'inherit', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 4 }}
                      onClick={() => setFilter('failed')}>
                <XCircle size={12} /> {counts.failed} failed
              </button>
            )}
          </div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        <div className="table-toolbar">
          <div className="chip-group" role="tablist" aria-label="Filter jobs">
            {FILTERS.map(f => {
              const n = f.key === 'all' ? jobs.length : jobs.filter(j => f.test(j.state)).length;
              return (
                <button key={f.key} role="tab" aria-selected={filter === f.key}
                        className={`chip ${filter === f.key ? 'active' : ''}`} onClick={() => setFilter(f.key)}>
                  {f.label} <span className="chip-count">{n}</span>
                </button>
              );
            })}
            {counts.total > jobs.length && (
              <span className="muted" style={{ fontSize: '0.78rem', alignSelf: 'center' }}>
                latest {jobs.length} of {formatNumber(counts.total)}
              </span>
            )}
          </div>
          <div className="input-wrap" style={{ width: 260, maxWidth: '100%' }}>
            <Search size={14} className="input-icon" />
            <input
              className="form-input"
              style={{ height: 32, fontSize: '0.86rem' }}
              placeholder="Search by job, repo or error"
              value={query}
              onChange={e => setQuery(e.target.value)}
              aria-label="Search jobs"
            />
          </div>
        </div>

        {isLoading && <LoadingState label="Loading jobs…" />}
        {isError && (
          <EmptyState
            icon={<WifiOff size={20} />}
            title="Can't reach the API gateway"
            description="Check that the gateway is running on the URL in VITE_API_URL. This page retries every two seconds."
          />
        )}
        {!isLoading && !isError && jobs.length === 0 && (
          <EmptyState
            icon={<Inbox size={20} />}
            tone="accent"
            title="No jobs yet"
            description="Describe a bug in plain English, or send a GitHub webhook, and GitOracle will start investigating."
            action={canWrite ? <Link to="/fix" className="btn btn-primary"><Wand2 size={15} /> Ask to fix</Link> : undefined}
          />
        )}
        {!isLoading && !isError && jobs.length > 0 && visible.length === 0 && (
          <EmptyState icon={<Search size={20} />} title="No matching jobs" description="Try a different filter or search term." />
        )}
        {!isLoading && !isError && visible.length > 0 && (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Job</th>
                  <th>Repository &amp; error</th>
                  <th>Status</th>
                  <th className="num">Tokens</th>
                  <th className="num">Started</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((job) => (
                  <tr key={job.id} className="clickable" onClick={() => navigate(`/job/${job.id}`)}>
                    <td>
                      <Link to={`/job/${job.id}`} className="mono link" onClick={(e) => e.stopPropagation()}>
                        {job.id.substring(0, 8)}
                      </Link>
                    </td>
                    <td style={{ maxWidth: 420 }}>
                      <div className="cell-primary truncate">{shortRepo(job.repo)}</div>
                      <div className="cell-sub truncate" title={describe(job)}>{describe(job) || 'No description'}</div>
                    </td>
                    <td><StatusBadge state={job.state} /></td>
                    <td className="num mono tabular">{job.tokenBudgetUsed ? formatNumber(job.tokenBudgetUsed) : '—'}</td>
                    <td className="num" style={{ whiteSpace: 'nowrap', fontSize: '0.84rem' }} title={formatTime(job.createdAt, true)}>
                      {relativeTime(job.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </motion.div>
    </motion.div>
  );
}
