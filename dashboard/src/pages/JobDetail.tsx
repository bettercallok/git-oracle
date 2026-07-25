import { useParams, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowLeft, CheckCircle2, Loader2, AlertCircle } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';

interface Job {
  id: string;
  repoUrl: string;
  errorMessage: string;
  status: string;
  tokensUsed: number;
  createdAt: string;
}

const fetchJob = async (id: string): Promise<Job> => {
  const { data } = await apiClient.get(`/jobs/${id}`);
  return data;
};

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

  const { data: job, isLoading, isError } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => fetchJob(jobId!),
    enabled: !!jobId,
    refetchInterval: 2000,
  });

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '4rem' }}>
        <Loader2 size={32} className="spinner" style={{ color: 'var(--accent)' }} />
      </div>
    );
  }

  if (isError || !job) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--danger)' }}>
        <AlertCircle size={32} style={{ marginBottom: 16 }} />
        <h2>Error loading job details</h2>
        <p>Could not find job {jobId}</p>
        <Link to="/" className="btn btn-outline" style={{ marginTop: 16 }}>Back to Feed</Link>
      </div>
    );
  }

  const isSuccess = job.status === 'SUCCESS';

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 8 }}>
          <Link to="/" className="btn btn-outline" style={{ padding: '6px 10px', textDecoration: 'none' }}>
            <ArrowLeft size={16} />
          </Link>
          <h1 className="page-title" style={{ marginBottom: 0 }}>Job {job.id.substring(0,8)}</h1>
          <span className={`badge ${isSuccess ? 'badge-success' : 'badge-warning'}`} style={{ padding: '4px 8px' }}>
            {isSuccess ? <CheckCircle2 size={12} style={{ marginRight: 2 }} /> : <Loader2 size={12} style={{ marginRight: 2 }} />} 
            {job.status}
          </span>
        </div>
        <p className="page-description" style={{ marginLeft: 50 }}>Full pipeline timeline</p>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Repository</div>
          <div style={{ fontSize: '1.1rem', fontWeight: 500, color: 'var(--text-primary)' }}>{job.repoUrl.replace('https://github.com/','')}</div>
        </div>
        <div className="card">
          <div className="stat-label">Tokens Used</div>
          <div className="mono" style={{ fontSize: '0.9rem', color: 'var(--text-primary)' }}>{job.tokensUsed}</div>
        </div>
        <div className="card">
          <div className="stat-label">Status</div>
          <div className="stat-value">{job.status}</div>
        </div>
        <div className="card">
          <div className="stat-label">When</div>
          <div className="stat-value" style={{ fontSize: '1rem' }}>{new Date(job.createdAt).toLocaleTimeString()}</div>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="card" style={{ padding: '28px 32px' }}>
        <h2 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: 24 }}>Error Message</h2>
        <div className="mono" style={{ padding: 16, background: 'var(--bg-lighter)', borderRadius: 8, color: 'var(--text-primary)', whiteSpace: 'pre-wrap' }}>
          {job.errorMessage}
        </div>
        
        <h2 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-primary)', marginTop: 32, marginBottom: 24 }}>Pipeline Timeline</h2>
        <div className="timeline">
            <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }}>
              <div className="timeline-dot" />
              <div className="timeline-time">{new Date(job.createdAt).toLocaleTimeString()}</div>
              <div className="timeline-title">Job Created</div>
              <div className="timeline-body">GitOracle orchestrator began processing the event.</div>
            </motion.div>
            
            {job.status === 'SUCCESS' && (
              <motion.div className="timeline-item" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.2 }}>
                <div className="timeline-dot" style={{ borderColor: 'var(--success)' }} />
                <div className="timeline-time">Completed</div>
                <div className="timeline-title">Job Finished</div>
                <div className="timeline-body">Agents successfully resolved the issue.</div>
              </motion.div>
            )}
        </div>
      </motion.div>
    </motion.div>
  );
}
