import { motion } from 'framer-motion';
import { useState, useEffect } from 'react';

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

const MOCK_PROMPTS: PromptVersion[] = [
  { agent: 'Planner', version: 'v3.2', accuracy: 0.94, avgTokens: 1850, active: true },
  { agent: 'Planner', version: 'v3.1', accuracy: 0.91, avgTokens: 2100, active: false },
  { agent: 'Planner', version: 'v3.0', accuracy: 0.87, avgTokens: 2400, active: false },
  { agent: 'Fixer', version: 'v2.5', accuracy: 0.89, avgTokens: 1200, active: true },
  { agent: 'Fixer', version: 'v2.4', accuracy: 0.85, avgTokens: 1450, active: false },
  { agent: 'Fixer', version: 'v2.3', accuracy: 0.82, avgTokens: 1600, active: false },
];

interface EvalRun {
  runId: string;
  date: string;
  accuracy: number;
  totalCases: number;
}

const MOCK_EVALS: EvalRun[] = [
  { runId: 'eval-994', date: 'Jul 23', accuracy: 0.98, totalCases: 50 },
  { runId: 'eval-993', date: 'Jul 22', accuracy: 0.96, totalCases: 50 },
  { runId: 'eval-992', date: 'Jul 21', accuracy: 0.94, totalCases: 48 },
  { runId: 'eval-991', date: 'Jul 20', accuracy: 0.91, totalCases: 48 },
  { runId: 'eval-990', date: 'Jul 19', accuracy: 0.88, totalCases: 45 },
  { runId: 'eval-989', date: 'Jul 18', accuracy: 0.92, totalCases: 45 },
];

const FEEDBACK = [
  { label: 'Merged', pct: 72, color: 'var(--success)' },
  { label: 'Approved', pct: 18, color: 'var(--info)' },
  { label: 'Rejected', pct: 7, color: 'var(--warning)' },
  { label: 'Reverted', pct: 3, color: 'var(--danger)' },
];

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function EvalDashboard() {
  const accuracy = useNumberTicker(98, 1000);
  const cases = useNumberTicker(50, 1000);
  const mergeRate = useNumberTicker(92, 1000);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Eval Dashboard</h1>
        <p className="page-description">Prompt performance, feedback outcomes, and accuracy trends</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Latest Accuracy</div>
          <div className="stat-value">{accuracy}%</div>
          <div className="stat-subtitle mono" style={{ color: 'var(--text-muted)' }}>{MOCK_EVALS[0].runId}</div>
        </div>
        <div className="card">
          <div className="stat-label">Eval Cases</div>
          <div className="stat-value">{cases}</div>
        </div>
        <div className="card">
          <div className="stat-label">PR Merge Rate</div>
          <div className="stat-value" style={{ color: 'var(--success)' }}>{mergeRate}%</div>
        </div>
        <div className="card">
          <div className="stat-label">Regressions</div>
          <div className="stat-value" style={{ color: 'var(--danger)' }}>1</div>
          <div className="stat-subtitle">in last 6 runs</div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 24 }}>
        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--text-primary)', marginBottom: 20 }}>Accuracy Trend</h2>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, height: 160 }}>
            {MOCK_EVALS.slice().reverse().map((run, i) => (
              <div key={run.runId} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
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
                <div className="mono" style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>{run.date}</div>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h2 style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--text-primary)', marginBottom: 20 }}>PR Feedback</h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 18, paddingTop: 6 }}>
            {FEEDBACK.map((item, i) => (
              <div key={item.label}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                  <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>{item.label}</span>
                  <span className="mono" style={{ fontSize: '0.85rem', color: 'var(--text-primary)' }}>{item.pct}%</span>
                </div>
                <div style={{ height: 6, background: 'var(--border-strong)', borderRadius: 3, overflow: 'hidden' }}>
                  <motion.div 
                    initial={{ width: 0 }}
                    animate={{ width: `${item.pct}%` }}
                    transition={{ delay: 0.3 + (i * 0.1), duration: 0.8, ease: 'easeOut' }}
                    style={{ height: '100%', background: item.color, borderRadius: 3 }} 
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border-subtle)' }}>
          <h2 style={{ fontSize: '1rem', fontWeight: 500, color: 'var(--text-primary)' }}>Prompt Versions</h2>
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
            {MOCK_PROMPTS.map((p) => (
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
    </motion.div>
  );
}
