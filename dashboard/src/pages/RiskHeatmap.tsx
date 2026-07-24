import { motion } from 'framer-motion';
import { Flame } from 'lucide-react';

interface FileRisk {
  file: string;
  bugs: number;
  heat: 'low' | 'medium' | 'high' | 'critical';
}

const MOCK_FILES: FileRisk[] = [
  { file: 'PaymentService.java', bugs: 14, heat: 'critical' },
  { file: 'OrderQueue.java', bugs: 11, heat: 'critical' },
  { file: 'UserRepository.java', bugs: 9, heat: 'high' },
  { file: 'TokenValidator.java', bugs: 8, heat: 'high' },
  { file: 'InvoiceCalculator.java', bugs: 7, heat: 'high' },
  { file: 'EmailSender.java', bugs: 5, heat: 'medium' },
  { file: 'StockManager.java', bugs: 5, heat: 'medium' },
  { file: 'IndexBuilder.java', bugs: 4, heat: 'medium' },
  { file: 'NotificationHandler.java', bugs: 3, heat: 'low' },
  { file: 'ConfigLoader.java', bugs: 2, heat: 'low' },
  { file: 'HealthCheck.java', bugs: 1, heat: 'low' },
  { file: 'MetricsExporter.java', bugs: 1, heat: 'low' },
  { file: 'CacheManager.java', bugs: 3, heat: 'medium' },
  { file: 'SessionStore.java', bugs: 6, heat: 'high' },
  { file: 'WebhookController.java', bugs: 2, heat: 'low' },
  { file: 'MigrationRunner.java', bugs: 4, heat: 'medium' },
];

interface DevRisk {
  name: string;
  commits: number;
  bugRate: number;
}

const MOCK_DEVS: DevRisk[] = [
  { name: 'alice.chen', commits: 142, bugRate: 0.12 },
  { name: 'bob.kumar', commits: 98, bugRate: 0.08 },
  { name: 'charlie.wright', commits: 201, bugRate: 0.15 },
  { name: 'diana.park', commits: 67, bugRate: 0.03 },
  { name: 'evan.james', commits: 134, bugRate: 0.11 },
];

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function RiskHeatmap() {
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
          {MOCK_FILES.map((f, i) => (
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
            {MOCK_DEVS.sort((a, b) => b.bugRate - a.bugRate).map(dev => (
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
