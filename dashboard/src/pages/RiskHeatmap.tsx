import { motion } from 'framer-motion';
import { Flame, Loader2, AlertCircle } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';

interface FileRisk {
  file: string;
  bugs: number;
  heat: 'low' | 'medium' | 'high' | 'critical';
}

interface DevRisk {
  name: string;
  commits: number;
  bugRate: number;
}

interface RiskData {
  files: FileRisk[];
  developers: DevRisk[];
}

const fetchRiskData = async (): Promise<RiskData> => {
  const { data } = await apiClient.get('/risk');
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

export default function RiskHeatmap() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['riskHeatmap'],
    queryFn: fetchRiskData,
    refetchInterval: 30000,
  });

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: '4rem' }}>
        <Loader2 size={32} className="spinner" style={{ color: 'var(--accent)' }} />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--danger)' }}>
        <AlertCircle size={32} style={{ marginBottom: 16 }} />
        <h2>Error loading risk data</h2>
        <p>Could not fetch from Neo4j backend</p>
      </div>
    );
  }

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <motion.div variants={itemVariants} className="page-header">
        <h1 className="page-title">Risk Heatmap</h1>
        <p className="page-description">Files and developers ranked by historical bug rate</p>
      </motion.div>

      <motion.div variants={itemVariants} className="card" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 20 }}>
          <Flame size={18} color="var(--danger)" />
          <h2 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)' }}>File Risk Matrix</h2>
        </div>
        <div className="heatmap-grid">
          {data.files.map((f, i) => (
            <motion.div 
              key={f.file}
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ delay: 0.1 + (i * 0.02) }}
              className={`heatmap-cell heat-${f.heat}`}
            >
              <div className="file-name">{f.file}</div>
              <div className="bug-count">{f.bugs}</div>
            </motion.div>
          ))}
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--border-subtle)' }}>
          <h2 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-primary)' }}>Developer Risk Profile</h2>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Developer</th>
              <th>Commits</th>
              <th>Bug Rate</th>
              <th>Risk</th>
            </tr>
          </thead>
          <tbody>
            {data.developers.sort((a, b) => b.bugRate - a.bugRate).map(dev => (
              <tr key={dev.name}>
                <td className="mono" style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{dev.name}</td>
                <td className="mono">{dev.commits}</td>
                <td className="mono" style={{ color: 'var(--text-primary)' }}>{(dev.bugRate * 100).toFixed(1)}%</td>
                <td>
                  <span className={`badge ${dev.bugRate > 0.12 ? 'badge-danger' : dev.bugRate > 0.08 ? 'badge-warning' : 'badge-success'}`}>
                    {dev.bugRate > 0.12 ? 'HIGH' : dev.bugRate > 0.08 ? 'MEDIUM' : 'LOW'}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </motion.div>
    </motion.div>
  );
}
