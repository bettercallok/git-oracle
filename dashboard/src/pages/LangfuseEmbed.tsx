import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import { ExternalLink, BrainCircuit, Hash, Layers, Timer } from 'lucide-react';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader } from '../components/ui';

// Same source as JobDetail's "LLM trace" link.
const LANGFUSE_URL = import.meta.env.VITE_LANGFUSE_URL || 'http://localhost:3002';

/**
 * This page used to embed Langfuse in an iframe pointed at :3000, where
 * nothing listens — and on the right port it still couldn't work: Langfuse
 * sends `frame-ancestors 'none'` / `X-Frame-Options: SAMEORIGIN`, and this
 * dashboard's own CSP (`default-src 'self'`) blocks other-origin frames. It
 * always rendered blank. Langfuse opens in its own tab instead.
 */
export default function LangfuseEmbed() {
  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 820 }}>
      <PageHeader
        title="LLM traces"
        description="Every model call an agent makes is recorded in Langfuse — prompts, responses, token counts and latency."
        actions={
          <a href={LANGFUSE_URL} target="_blank" rel="noreferrer" className="btn btn-primary">
            Open Langfuse <ExternalLink size={14} />
          </a>
        }
      />

      <motion.div variants={itemVariants} className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ padding: '28px 28px 24px', display: 'flex', gap: 18, alignItems: 'flex-start' }}>
          <div className="empty-icon accent" style={{ margin: 0, flexShrink: 0 }}><BrainCircuit size={20} /></div>
          <div>
            <div style={{ fontWeight: 600, fontSize: '1.02rem' }}>One trace per job</div>
            <p style={{ color: 'var(--text-secondary)', marginTop: 4, lineHeight: 1.6 }}>
              Traces are keyed by job id, so the quickest way in is <strong>LLM trace</strong> on a job's page.
              Langfuse can't be embedded here — its own security headers forbid it — so it opens in a new tab.
            </p>
          </div>
        </div>
        <div className="grid-2" style={{ gap: 0, borderTop: '1px solid var(--border-subtle)' }}>
          {[
            { icon: <Hash size={14} />, title: 'Find a job', body: <>Search Langfuse for the job id shown on <Link to="/" className="link">Jobs</Link>.</> },
            { icon: <Layers size={14} />, title: 'Follow the agents', body: 'Investigator, planner and fixer calls nest under the same trace.' },
            { icon: <Timer size={14} />, title: 'Spot slow steps', body: 'Latency and token usage are recorded for every call.' },
            { icon: <ExternalLink size={14} />, title: 'Endpoint', body: <span className="mono" style={{ wordBreak: 'break-all' }}>{LANGFUSE_URL}</span> },
          ].map((item, i) => (
            <div key={item.title} style={{
              padding: '16px 28px',
              borderTop: i > 1 ? '1px solid var(--border-subtle)' : undefined,
              borderLeft: i % 2 === 1 ? '1px solid var(--border-subtle)' : undefined,
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 500, color: 'var(--text-primary)', fontSize: '0.9rem' }}>
                <span className="muted">{item.icon}</span> {item.title}
              </div>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: 3 }}>{item.body}</div>
            </div>
          ))}
        </div>
      </motion.div>
    </motion.div>
  );
}
