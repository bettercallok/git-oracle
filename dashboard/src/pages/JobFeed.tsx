import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { Activity, CheckCircle2, AlertTriangle, Layers, Search, Wand2, Inbox, WifiOff } from 'lucide-react';
import { apiClient } from '../api/client';
import { stateMeta, isRunning, isSucceeded, needsAttention, GROUPS, type StateGroup } from '../utils/jobState';
import { containerVariants, itemVariants } from '../utils/motion';
import { formatNumber, formatTime, relativeTime, shortRepo } from '../utils/format';
import { useNumberTicker } from '../utils/useNumberTicker';
import { PageHeader, EmptyState, LoadingState } from '../components/ui';

interface Job {
  id: string;
  repo: string;
  commitHash: string;
  errorMessage: string;
  state: string; // see utils/jobState.ts
  createdAt: string;
  tokensUsed: number;
}

const fetchJobs = async (): Promise<Job[]> => {
  const { data } = await apiClient.get('/jobs');
  return data;
};

type Filter = 'all' | 'running' | 'succeeded' | 'attention';

const FILTERS: { key: Filter; label: string; test: (s: string) => boolean }[] = [
  { key: 'all', label: 'All', test: () => true },
  { key: 'running', label: 'Running', test: isRunning },
  { key: 'succeeded', label: 'PR opened', test: isSucceeded },
  { key: 'attention', label: 'Needs attention', test: needsAttention },
];

function StatusBadge({ state }: { state: string }) {
  const meta = stateMeta(state);
  return <span className={`badge ${meta.badge} ${meta.group === 'running' ? 'live' : ''}`}>{meta.label}</span>;
}

export default function JobFeed() {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');

  const { data: jobs = [], isLoading, isError } = useQuery({
    queryKey: ['jobs'],
    queryFn: fetchJobs,
    refetchInterval: 2000, // live poll
  });

  const counts = useMemo(() => {
    const byGroup = {} as Record<StateGroup, number>;
    for (const j of jobs) {
      const g = stateMeta(j.state).group;
      byGroup[g] = (byGroup[g] ?? 0) + 1;
    }
    return {
      byGroup,
      running: jobs.filter(j => isRunning(j.state)).length,
      succeeded: jobs.filter(j => isSucceeded(j.state)).length,
      attention: jobs.filter(j => needsAttention(j.state)).length,
    };
  }, [jobs]);

  const total = useNumberTicker(jobs.length);
  const running = useNumberTicker(counts.running);
  const succeeded = useNumberTicker(counts.succeeded);
  const attention = useNumberTicker(counts.attention);

  // Escalated jobs aren't finished — a human may still send them back through.
  const finished = counts.succeeded + (counts.byGroup.failed ?? 0) + (counts.byGroup.closed ?? 0);
  const successRate = finished > 0 ? Math.round((counts.succeeded / finished) * 100) : null;

  const visible = useMemo(() => {
    const f = FILTERS.find(x => x.key === filter)!;
    const q = query.trim().toLowerCase();
    return jobs.filter(j =>
      f.test(j.state) &&
      (!q || j.id.includes(q) || shortRepo(j.repo).toLowerCase().includes(q) || (j.errorMessage || '').toLowerCase().includes(q)),
    );
  }, [jobs, filter, query]);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Jobs"
        description="Every bug GitOracle is working on, from first report to pull request. Updates live."
        actions={<Link to="/fix" className="btn btn-primary"><Wand2 size={15} /> Ask to fix</Link>}
      />

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-head">
            <span className="stat-label">Total jobs</span>
            <span className="stat-icon"><Layers size={15} /></span>
          </div>
          <div className="stat-value">{total}</div>
          {jobs.length > 0 ? (
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
            <span className="stat-label">Needs attention</span>
            <span className={`stat-icon ${counts.attention > 0 ? 'warning' : ''}`}><AlertTriangle size={15} /></span>
          </div>
          <div className="stat-value">{attention}</div>
          <div className="stat-subtitle">
            {counts.attention > 0 ? <Link to="/escalations" className="link">Review escalations →</Link> : 'Nothing waiting on you'}
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
            action={<Link to="/fix" className="btn btn-primary"><Wand2 size={15} /> Ask to fix</Link>}
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
                      <div className="cell-sub truncate" title={job.errorMessage}>{job.errorMessage || 'No description'}</div>
                    </td>
                    <td><StatusBadge state={job.state} /></td>
                    <td className="num mono tabular">{formatNumber(job.tokensUsed)}</td>
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
