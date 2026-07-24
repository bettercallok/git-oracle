import { useState } from 'react';
import { Save, Server, Shield, Brain } from 'lucide-react';
import { motion } from 'framer-motion';

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } }
};
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 24 } }
};

export default function AdminSettings() {
  const [saving, setSaving] = useState(false);

  // Form State
  const [config, setConfig] = useState({
    llamaUrl: 'http://localhost:8080/v1',
    kafkaBroker: 'localhost:9092',
    qdrantEndpoint: 'http://localhost:6333',
    plannerEnabled: true,
    fixerEnabled: true,
    requireGuardrails: true,
    autoCommit: false,
  });

  const handleSave = () => {
    setSaving(true);
    // Simulate save
    setTimeout(() => {
      setSaving(false);
      alert('Settings saved locally. Backend integration pending.');
    }, 800);
  };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 900 }}>
      <motion.div variants={itemVariants} className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h1 className="page-title">Admin Settings</h1>
          <p className="page-description" style={{ color: 'var(--text-secondary)' }}>Configure system endpoints, AI agents, and security constraints</p>
        </div>
        <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
          <Save size={16} />
          {saving ? 'Saving...' : 'Save Changes'}
        </button>
      </motion.div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
        {/* System Configuration */}
        <motion.div variants={itemVariants} className="card">
          <h2 className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Server size={18} /> System Infrastructure
          </h2>
          
          <div className="form-group">
            <label className="form-label">LLM API Endpoint (llama.cpp)</label>
            <input 
              type="text" 
              className="form-input" 
              value={config.llamaUrl}
              onChange={e => setConfig({...config, llamaUrl: e.target.value})}
            />
          </div>

          <div className="form-group">
            <label className="form-label">Kafka Broker URL</label>
            <input 
              type="text" 
              className="form-input" 
              value={config.kafkaBroker}
              onChange={e => setConfig({...config, kafkaBroker: e.target.value})}
            />
          </div>

          <div className="form-group" style={{ marginBottom: 0 }}>
            <label className="form-label">Qdrant Vector DB Endpoint</label>
            <input 
              type="text" 
              className="form-input" 
              value={config.qdrantEndpoint}
              onChange={e => setConfig({...config, qdrantEndpoint: e.target.value})}
            />
          </div>
        </motion.div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          {/* Agent Controls */}
          <motion.div variants={itemVariants} className="card">
            <h2 className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Brain size={18} /> Agent Fleet Controls
            </h2>
            
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text-primary)', marginBottom: 2 }}>Planner Agent</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Analyzes issues and builds execution plans</div>
              </div>
              <label className="toggle-switch">
                <input 
                  type="checkbox" 
                  checked={config.plannerEnabled}
                  onChange={e => setConfig({...config, plannerEnabled: e.target.checked})}
                />
                <span className="toggle-slider"></span>
              </label>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text-primary)', marginBottom: 2 }}>Fixer Agent</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Executes code modifications directly</div>
              </div>
              <label className="toggle-switch">
                <input 
                  type="checkbox" 
                  checked={config.fixerEnabled}
                  onChange={e => setConfig({...config, fixerEnabled: e.target.checked})}
                />
                <span className="toggle-slider"></span>
              </label>
            </div>
          </motion.div>

          {/* Security & Pipeline */}
          <motion.div variants={itemVariants} className="card">
            <h2 className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Shield size={18} /> Security & Pipeline
            </h2>
            
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text-primary)', marginBottom: 2 }}>Require Guardrails Validation</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Block execution if AI confidence is low</div>
              </div>
              <label className="toggle-switch">
                <input 
                  type="checkbox" 
                  checked={config.requireGuardrails}
                  onChange={e => setConfig({...config, requireGuardrails: e.target.checked})}
                />
                <span className="toggle-slider"></span>
              </label>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text-primary)', marginBottom: 2 }}>Enable Auto-Commit</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Allow agents to commit bypassing human review</div>
              </div>
              <label className="toggle-switch">
                <input 
                  type="checkbox" 
                  checked={config.autoCommit}
                  onChange={e => setConfig({...config, autoCommit: e.target.checked})}
                />
                <span className="toggle-slider"></span>
              </label>
            </div>
          </motion.div>
        </div>
      </div>
    </motion.div>
  );
}
