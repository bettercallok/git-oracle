import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';

const formatTime = (raw: string | null | undefined): string => {
  if (!raw) return '—';
  const d = new Date(raw);
  if (isNaN(d.getTime()) || d.getFullYear() < 2000) return '—';
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
  });
};

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

// --- Types & Data ---
interface Job {
  id: string;
  repo: string;
  commitHash: string;
  errorMessage: string;
  state: string; // QUEUED, RUNNING, INVESTIGATING, FIXING, TESTING, REGENERATING, PR_OPENED, SUCCESS, FAILED, ESCALATED
  createdAt: string;
  tokensUsed: number;
}

const fetchJobs = async (): Promise<Job[]> => {
  const { data } = await apiClient.get('/jobs');
  return data;
};

const SPARKLINE = [2, 4, 3, 7, 5, 8, 4, 6, 9, 5, 8, 7];

const statusBadge = (state: string) => {
  const map: Record<string, string> = { 
    SUCCESS: 'badge-success', 
    PR_OPENED: 'badge-success',
    RUNNING: 'badge-warning', 
    INVESTIGATING: 'badge-warning',
    FIXING: 'badge-warning',
    TESTING: 'badge-warning',
    REGENERATING: 'badge-warning',
    QUEUED: 'badge-info',
    FAILED: 'badge-danger', 
    ESCALATED: 'badge-danger' 
  };
  return <span className={`badge ${map[state] || 'badge-info'}`}>{state || 'UNKNOWN'}</span>;
};

// --- Animations ---
const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function JobFeed() {
  const navigate = useNavigate();
  const { data: jobs = [], isLoading, isError } = useQuery({
    queryKey: ['jobs'],
    queryFn: fetchJobs,
    refetchInterval: 2000, // Live poll every 2s
  });

  const total = useNumberTicker(jobs.length, 1000);
  const success = useNumberTicker(jobs.filter(j => ['SUCCESS', 'PR_OPENED'].includes(j.state)).length, 1000);
  const running = useNumberTicker(jobs.filter(j => ['RUNNING', 'INVESTIGATING', 'TESTING', 'REGENERATING'].includes(j.state)).length, 1000);
  const failed = useNumberTicker(jobs.filter(j => ['FAILED', 'ESCALATED'].includes(j.state)).length, 1000);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Job Feed</h1>
        <p className="page-description">Live view of agent jobs across repositories</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Total Jobs</div>
          <div className="stat-value">{total}</div>
          <div className="sparkline-container">
            {SPARKLINE.map((v, i) => (
              <motion.div 
                key={i} 
                className="sparkline-bar" 
                initial={{ height: 0 }}
                animate={{ height: `${v * 3}px` }}
                transition={{ delay: 0.2 + (i * 0.03), type: 'spring' as const }}
              />
            ))}
          </div>
        </div>
        <div className="card">
          <div className="stat-label">Successful</div>
          <div className="stat-value" style={{ color: 'var(--success)' }}>{success}</div>
          <div className="stat-subtitle">success count</div>
        </div>
        <div className="card">
          <div className="stat-label">Running</div>
          <div className="stat-value" style={{ color: 'var(--warning)' }}>{running}</div>
          <div className="stat-subtitle">active count</div>
        </div>
        <div className="card">
          <div className="stat-label">Failed</div>
          <div className="stat-value" style={{ color: 'var(--danger)' }}>{failed}</div>
          <div className="stat-subtitle">needs review</div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        {isLoading && <p>Loading live jobs...</p>}
        {isError && <p>Error connecting to API Gateway.</p>}
        {!isLoading && !isError && (
          <table className="table">
            <thead>
              <tr>
                <th>Job</th>
                <th>Repository</th>
                <th>Error</th>
                <th>Tokens Used</th>
                <th>Status</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id} onClick={() => navigate(`/job/${job.id}`)} style={{ cursor: 'pointer' }} className="hover-row">
                  <td>
                    <Link to={`/job/${job.id}`} className="mono" onClick={(e) => e.stopPropagation()} style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                      {job.id.substring(0,8)}
                    </Link>
                  </td>
                  <td style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{job.repo.replace('https://github.com/','')}</td>
                  <td style={{ maxWidth: 280, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{job.errorMessage}</td>
                  <td className="mono">{job.tokensUsed}</td>
                  <td>{statusBadge(job.state)}</td>
                  <td style={{ fontSize: '0.82rem' }}>{formatTime(job.createdAt)}</td>
                </tr>
              ))}
              {jobs.length === 0 && (
                <tr>
                  <td colSpan={6} style={{ textAlign: 'center', padding: '2rem' }}>No active jobs found in database.</td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </motion.div>
    </motion.div>
  );
}
