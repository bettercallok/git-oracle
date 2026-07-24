import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Check, X, ShieldAlert } from 'lucide-react';

interface Escalation {
  id: string;
  repo: string;
  error: string;
  confidence: number;
  reason: string;
  agent: string;
  timestamp: string;
}

const MOCK_ESCALATIONS: Escalation[] = [
  { id: 'esc-401', repo: 'acme/auth-gateway', error: 'StackOverflowError in TokenValidator.java:56', confidence: 0.38, reason: 'Confidence below threshold (0.50)', agent: 'Planner', timestamp: '8 min ago' },
  { id: 'esc-402', repo: 'acme/payments-api', error: 'ConcurrentModificationException in TransactionLog.java:112', confidence: 0.42, reason: 'Multiple conflicting fix strategies', agent: 'Fixer', timestamp: '22 min ago' },
  { id: 'esc-403', repo: 'acme/order-engine', error: 'SecurityException in AdminController.java:78', confidence: 0.21, reason: 'Security-sensitive file — requires human review', agent: 'Guardrails', timestamp: '35 min ago' },
  { id: 'esc-404', repo: 'acme/billing-core', error: 'DataIntegrityViolationException in InvoiceDAO.java:203', confidence: 0.44, reason: 'Database migration required', agent: 'Planner', timestamp: '1 hr ago' },
];

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function EscalationQueue() {
  const [escalations, setEscalations] = useState(MOCK_ESCALATIONS);

  const handleAction = (id: string, _action: 'approve' | 'reject') => {
    setEscalations(prev => prev.filter(e => e.id !== id));
  };

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
            {escalations.length > 0
              ? (escalations.reduce((s, e) => s + e.confidence, 0) / escalations.length).toFixed(2)
              : '—'}
          </div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
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
                    <span className="mono" style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{esc.id}</span>
                    <span className="badge badge-warning">Conf: {esc.confidence.toFixed(2)}</span>
                    <span className="mono" style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{esc.timestamp}</span>
                  </div>
                  <div style={{ fontWeight: 500, marginBottom: 4, color: 'var(--text-primary)' }}>{esc.repo}</div>
                  <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: 12 }}>{esc.error}</div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.8rem', color: 'var(--danger)', fontWeight: 500 }}>
                    <ShieldAlert size={14} /> {esc.reason}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                  <motion.button whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.95 }} className="btn btn-success" onClick={() => handleAction(esc.id, 'approve')}>
                    <Check size={16} /> Approve
                  </motion.button>
                  <motion.button whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.95 }} className="btn btn-outline" onClick={() => handleAction(esc.id, 'reject')} style={{ color: 'var(--danger)', borderColor: 'var(--danger-bg)' }}>
                    <X size={16} /> Reject
                  </motion.button>
                </div>
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
      </motion.div>
    </motion.div>
  );
}
