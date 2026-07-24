import { motion } from 'framer-motion';
import { ExternalLink } from 'lucide-react';

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function LangfuseEmbed() {
  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <motion.div variants={itemVariants} className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h1 className="page-title">Langfuse Traces</h1>
          <p className="page-description">Deep observability for LLM calls and agent reasoning</p>
        </div>
        <a href="http://localhost:3000" target="_blank" rel="noreferrer" className="btn btn-outline" style={{ textDecoration: 'none' }}>
          Open in New Tab <ExternalLink size={16} />
        </a>
      </motion.div>

      <motion.div variants={itemVariants} className="card" style={{ flex: 1, padding: 0, overflow: 'hidden', minHeight: 'calc(100vh - 160px)' }}>
        <iframe
          src="http://localhost:3000"
          style={{ width: '100%', height: '100%', border: 'none', background: 'var(--bg-app)' }}
          title="Langfuse Dashboard"
        />
      </motion.div>
    </motion.div>
  );
}
