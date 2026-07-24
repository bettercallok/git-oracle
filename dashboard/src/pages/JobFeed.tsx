import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';

// --- Custom Number Ticker Hook ---
function useNumberTicker(target: number, duration: number = 800) {
  const [value, setValue] = useState(0);

  useEffect(() => {
    let startTimestamp: number | null = null;
    const step = (timestamp: number) => {
      if (!startTimestamp) startTimestamp = timestamp;
      const progress = Math.min((timestamp - startTimestamp) / duration, 1);
      // easeOutExpo
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
  error: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ESCALATED';
  agent: string;
  timestamp: string;
  duration: string;
}

const MOCK_JOBS: Job[] = [
  { id: 'job-1001', repo: 'acme/payments-api', error: 'NullPointerException in PaymentService.java:142', status: 'SUCCESS', agent: 'Fixer', timestamp: '2 min ago', duration: '34s' },
  { id: 'job-1002', repo: 'acme/user-service', error: 'IndexOutOfBoundsException in UserRepository.java:89', status: 'RUNNING', agent: 'Planner', timestamp: '45s ago', duration: '—' },
  { id: 'job-1003', repo: 'acme/order-engine', error: 'ConcurrentModificationException in OrderQueue.java:201', status: 'FAILED', agent: 'Fixer', timestamp: '5 min ago', duration: '1m 12s' },
  { id: 'job-1004', repo: 'acme/auth-gateway', error: 'StackOverflowError in TokenValidator.java:56', status: 'ESCALATED', agent: 'Planner', timestamp: '8 min ago', duration: '2m 03s' },
  { id: 'job-1005', repo: 'acme/notification-svc', error: 'TimeoutException in EmailSender.java:33', status: 'SUCCESS', agent: 'Fixer', timestamp: '12 min ago', duration: '22s' },
  { id: 'job-1006', repo: 'acme/inventory-api', error: 'IllegalStateException in StockManager.java:178', status: 'SUCCESS', agent: 'Fixer', timestamp: '18 min ago', duration: '45s' },
];

const SPARKLINE = [2, 4, 3, 7, 5, 8, 4, 6, 9, 5, 8, 7];

const statusBadge = (status: Job['status']) => {
  const map = { SUCCESS: 'badge-success', RUNNING: 'badge-warning', FAILED: 'badge-danger', ESCALATED: 'badge-info' };
  return <span className={`badge ${map[status]}`}>{status}</span>;
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
  const total = useNumberTicker(8, 1000);
  const success = useNumberTicker(3, 1000);
  const running = useNumberTicker(2, 1000);
  const failed = useNumberTicker(2, 1000);

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
          <div className="stat-subtitle">62.5% success rate</div>
        </div>
        <div className="card">
          <div className="stat-label">Running</div>
          <div className="stat-value" style={{ color: 'var(--warning)' }}>{running}</div>
          <div className="stat-subtitle">avg 1m 24s per job</div>
        </div>
        <div className="card">
          <div className="stat-label">Failed</div>
          <div className="stat-value" style={{ color: 'var(--danger)' }}>{failed}</div>
          <div className="stat-subtitle">1 pending escalation</div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        <table className="table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Repository</th>
              <th>Error</th>
              <th>Agent</th>
              <th>Status</th>
              <th>Duration</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {MOCK_JOBS.map((job) => (
              <tr key={job.id}>
                <td>
                  <Link to={`/job/${job.id}`} className="mono" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                    {job.id}
                  </Link>
                </td>
                <td style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{job.repo}</td>
                <td style={{ maxWidth: 280, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{job.error}</td>
                <td className="mono">{job.agent}</td>
                <td>{statusBadge(job.status)}</td>
                <td className="mono">{job.duration}</td>
                <td style={{ fontSize: '0.82rem' }}>{job.timestamp}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </motion.div>
    </motion.div>
  );
}
