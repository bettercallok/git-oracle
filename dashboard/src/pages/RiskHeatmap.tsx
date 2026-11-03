import { motion } from 'framer-motion';
import { Flame, Users, DatabaseZap } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader, EmptyState, LoadingState, Avatar } from '../components/ui';

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

const HEAT_ORDER = { critical: 0, high: 1, medium: 2, low: 3 } as const;

const riskLevel = (rate: number) =>
  rate > 0.12 ? { label: 'High', badge: 'badge-danger', color: 'var(--danger)' }
  : rate > 0.08 ? { label: 'Medium', badge: 'badge-warning', color: 'var(--warning)' }
  : { label: 'Low', badge: 'badge-success', color: 'var(--success)' };

export default function RiskHeatmap() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['riskHeatmap'],
    queryFn: fetchRiskData,
    refetchInterval: 30000,
  });

  const header = (
    <PageHeader
      title="Risk heatmap"
      description="Where bugs have historically come from, built from the repository graph in Neo4j. Refreshes every 30 seconds."
    />
  );

  if (isLoading) return <motion.div variants={containerVariants} initial="hidden" animate="show">{header}<LoadingState label="Loading risk data…" /></motion.div>;

  if (isError || !data) {
    return (
      <motion.div variants={containerVariants} initial="hidden" animate="show">
        {header}
        <div className="card">
          <EmptyState
            icon={<DatabaseZap size={20} />}
            title="Couldn't load risk data"
            description="The git-forensics service or its Neo4j database isn't responding."
          />
        </div>
      </motion.div>
    );
  }

  const files = [...data.files].sort((a, b) => HEAT_ORDER[a.heat] - HEAT_ORDER[b.heat] || b.bugs - a.bugs);
  const developers = [...data.developers].sort((a, b) => b.bugRate - a.bugRate);
  const maxRate = Math.max(0.01, ...developers.map(d => d.bugRate));

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      {header}

      <motion.div variants={itemVariants} className="card" style={{ marginBottom: 16 }}>
        <div className="card-header" style={{ flexWrap: 'wrap' }}>
          <div>
            <div className="card-title"><Flame size={16} /> Files by bug count</div>
            <div className="card-subtitle">Hottest first. Hover a file to see its full path.</div>
          </div>
          <div className="heat-legend">
            {(['low', 'medium', 'high', 'critical'] as const).map(h => (
              <span key={h} className={`heat-${h}`} style={{ background: 'none', border: 0, boxShadow: 'none' }}>
                <i /> {h[0].toUpperCase() + h.slice(1)}
              </span>
            ))}
          </div>
        </div>
        {files.length === 0 ? (
          <EmptyState icon={<Flame size={20} />} title="No file history yet" description="Files appear here once bug-fix commits are indexed." />
        ) : (
          <div className="heatmap-grid">
            {files.map((f, i) => (
              <motion.div
                key={f.file}
                initial={{ opacity: 0, y: 4 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: Math.min(i, 30) * 0.015 }}
                className={`heatmap-cell heat-${f.heat}`}
                title={f.file}
              >
                <div className="file-name">{f.file}</div>
                <div className="bug-count">{f.bugs}<span className="bug-unit">{f.bugs === 1 ? 'bug' : 'bugs'}</span></div>
              </motion.div>
            ))}
          </div>
        )}
      </motion.div>

      <motion.div variants={itemVariants} className="table-container">
        <div className="card-header" style={{ margin: 0, padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
          <div>
            <div className="card-title"><Users size={16} /> Authors by bug rate</div>
            <div className="card-subtitle">Share of an author's commits later linked to a bug. Context for review, not a scorecard.</div>
          </div>
        </div>
        {developers.length === 0 ? (
          <EmptyState icon={<Users size={20} />} title="No author history yet" />
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Author</th>
                  <th className="num">Commits</th>
                  <th style={{ width: '34%' }}>Bug rate</th>
                  <th>Risk</th>
                </tr>
              </thead>
              <tbody>
                {developers.map(dev => {
                  const level = riskLevel(dev.bugRate);
                  return (
                    <tr key={dev.name}>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <Avatar name={dev.name} size={24} />
                          <span className="cell-primary">{dev.name}</span>
                        </div>
                      </td>
                      <td className="num mono tabular">{dev.commits}</td>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <div className="meter" style={{ flex: 1 }}>
                            <div style={{ width: `${(dev.bugRate / maxRate) * 100}%`, background: level.color }} />
                          </div>
                          <span className="mono tabular" style={{ width: 48, textAlign: 'right', color: 'var(--text-primary)' }}>
                            {(dev.bugRate * 100).toFixed(1)}%
                          </span>
                        </div>
                      </td>
                      <td><span className={`badge ${level.badge}`}>{level.label}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </motion.div>
    </motion.div>
  );
}
