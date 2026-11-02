import { motion } from 'framer-motion';
import { ExternalLink, Activity } from 'lucide-react';

// Same source as JobDetail's "View LLM trace" link.
const LANGFUSE_URL = import.meta.env.VITE_LANGFUSE_URL || 'http://localhost:3002';

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

/**
 * This page used to embed Langfuse in an iframe pointed at :3000, where
 * nothing listens — and on the right port it still couldn't work: Langfuse
 * sends `frame-ancestors 'none'` / `X-Frame-Options: SAMEORIGIN`, and this
 * dashboard's own CSP (`default-src 'self'`) blocks other-origin frames. It
 * always rendered blank. Langfuse opens in its own tab instead.
 */
export default function LangfuseEmbed() {
  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Langfuse Traces</h1>
        <p className="page-description">Deep observability for LLM calls and agent reasoning</p>
      </motion.div>

      <motion.div variants={itemVariants} className="card" style={{ textAlign: 'center', padding: '48px 24px' }}>
        <div style={{ display: 'inline-flex', padding: 12, borderRadius: '50%', background: 'var(--info-bg)', color: 'var(--info)', marginBottom: 16 }}>
          <Activity size={28} />
        </div>
        <p style={{ fontSize: '1.05rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 8 }}>
          Every LLM call is traced in Langfuse, one trace per job
        </p>
        <p style={{ color: 'var(--text-secondary)', maxWidth: 560, margin: '0 auto 24px' }}>
          Langfuse can't be shown inside this page, so it opens in its own tab. To see one job's
          calls, use <strong>View LLM trace</strong> on that job's page.
        </p>
        <a href={LANGFUSE_URL} target="_blank" rel="noreferrer" className="btn btn-primary" style={{ textDecoration: 'none' }}>
          Open Langfuse <ExternalLink size={16} />
        </a>
        <p className="mono" style={{ color: 'var(--text-muted)', fontSize: '0.8rem', marginTop: 16 }}>{LANGFUSE_URL}</p>
      </motion.div>
    </motion.div>
  );
}
