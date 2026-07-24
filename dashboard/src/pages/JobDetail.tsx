import { useParams, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowLeft, CheckCircle2 } from 'lucide-react';

const MOCK_TIMELINE = [
  { time: '10:42:01', title: 'Error Ingested', body: 'NullPointerException detected in PaymentService.java:142 via CI pipeline webhook.' },
  { time: '10:42:03', title: 'Causal Analysis', body: 'Git forensics analyzed 47 commits. Root cause: commit a3f9b1c removed null-check in refactor.' },
  { time: '10:42:08', title: 'Planner Agent', body: 'Generated fix plan — restore null-guard before processPayment() call. Confidence: 0.94.' },
  { time: '10:42:12', title: 'Fixer Agent', body: 'Generated unified diff patch for PaymentService.java. +3 lines, -0 lines.' },
  { time: '10:42:18', title: 'Guardrails Check', body: 'Static analysis: clean. Security scan: no vulnerabilities. Diff size within budget.' },
  { time: '10:42:22', title: 'Test Runner', body: 'All 142 unit tests passed. 8 integration tests passed. Zero regressions.' },
  { time: '10:42:30', title: 'PR Created', body: 'PR #287 opened on acme/payments-api. Branch: gitoracle/fix-job-1001.' },
  { time: '10:44:15', title: 'PR Merged', body: 'Reviewer approved without changes. Merged to main. Memory reinforced with positive signal.' },
];

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

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 8 }}>
          <Link to="/" className="btn btn-outline" style={{ padding: '6px 10px', textDecoration: 'none' }}>
            <ArrowLeft size={16} />
          </Link>
          <h1 className="page-title" style={{ marginBottom: 0 }}>Job {jobId}</h1>
          <span className="badge badge-success" style={{ padding: '4px 8px' }}>
            <CheckCircle2 size={12} style={{ marginRight: 2 }} /> SUCCESS
          </span>
        </div>
        <p className="page-description" style={{ marginLeft: 50 }}>Full pipeline timeline</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Repository</div>
          <div style={{ fontSize: '1.1rem', fontWeight: 500, color: 'var(--text-primary)' }}>acme/payments-api</div>
        </div>
        <div className="card">
          <div className="stat-label">Error Type</div>
          <div className="mono" style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>NullPointerException</div>
        </div>
        <div className="card">
          <div className="stat-label">Confidence</div>
          <div className="stat-value">0.94</div>
        </div>
        <div className="card">
          <div className="stat-label">Duration</div>
          <div className="stat-value">34s</div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="card" style={{ padding: '28px 32px' }}>
        <h2 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 24 }}>Pipeline Timeline</h2>
        <div className="timeline">
          {MOCK_TIMELINE.map((event, i) => (
            <motion.div 
              className="timeline-item" 
              key={i}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.2 + (i * 0.08), type: 'spring' as const }}
            >
              <div className="timeline-dot" />
              <div className="timeline-time">{event.time}</div>
              <div className="timeline-title">{event.title}</div>
              <div className="timeline-body">{event.body}</div>
            </motion.div>
          ))}
        </div>
      </motion.div>
    </motion.div>
  );
}
