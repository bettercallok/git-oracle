import { useState } from 'react';
import { Save, Server, ShieldCheck, Bot, Check } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader, Alert } from '../components/ui';

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="toggle-switch">
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} aria-label={label} />
      <span className="toggle-slider" />
    </label>
  );
}

export default function AdminSettings() {
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const [config, setConfig] = useState({
    llmUrl: 'https://api.openai.com/v1/chat/completions',
    kafkaBroker: 'localhost:9092',
    qdrantEndpoint: 'http://localhost:6333',
    plannerEnabled: true,
    fixerEnabled: true,
    requireGuardrails: true,
    autoCommit: false,
  });
  const set = <K extends keyof typeof config>(key: K, value: (typeof config)[K]) => setConfig(c => ({ ...c, [key]: value }));

  const handleSave = () => {
    setSaving(true);
    // Not wired to the backend yet — see the notice at the top of the page.
    setTimeout(() => {
      setSaving(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    }, 500);
  };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show" style={{ maxWidth: 940 }}>
      <PageHeader
        title="Settings"
        description="Endpoints, agents and safety rules for this deployment."
        actions={
          <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saved ? <Check size={15} /> : <Save size={15} />}
            {saving ? 'Saving…' : saved ? 'Saved in this tab' : 'Save changes'}
          </button>
        }
      />

      <motion.div variants={itemVariants} style={{ marginBottom: 16 }}>
        <Alert tone="info" title="Preview">
          These controls aren't connected to the services yet, so changes here last only until you leave the page.
          The running configuration comes from the root <code className="inline">.env</code> — restart the services after editing it.
        </Alert>
      </motion.div>

      <AnimatePresence>
        {saved && (
          <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginBottom: 16 }}>
            <Alert tone="success">Saved for this tab. Nothing was sent to the backend.</Alert>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="grid-2" style={{ alignItems: 'start' }}>
        <motion.div variants={itemVariants} className="card">
          <h2 className="section-title"><Server size={16} /> Infrastructure</h2>

          <div className="field">
            <label className="form-label" htmlFor="llm-url">LLM endpoint</label>
            <input id="llm-url" type="text" className="form-input mono-input" value={config.llmUrl}
                   onChange={e => set('llmUrl', e.target.value)} />
            <div className="form-hint">
              Any OpenAI-compatible API. Must be the full <code className="inline">/chat/completions</code> URL (<code className="inline">LLM_BASE_URL</code>).
            </div>
          </div>

          <div className="field">
            <label className="form-label" htmlFor="kafka">Kafka broker</label>
            <input id="kafka" type="text" className="form-input mono-input" value={config.kafkaBroker}
                   onChange={e => set('kafkaBroker', e.target.value)} />
          </div>

          <div className="field">
            <label className="form-label" htmlFor="qdrant">Qdrant endpoint</label>
            <input id="qdrant" type="text" className="form-input mono-input" value={config.qdrantEndpoint}
                   onChange={e => set('qdrantEndpoint', e.target.value)} />
            <div className="form-hint">Vector store for retrieval context.</div>
          </div>
        </motion.div>

        <div className="stack-lg">
          <motion.div variants={itemVariants} className="card">
            <h2 className="section-title"><Bot size={16} /> Agents</h2>
            <div className="setting-row">
              <div>
                <div className="setting-title">Planner</div>
                <div className="setting-desc">Scopes each fix to specific files, functions and a line budget.</div>
              </div>
              <Toggle label="Planner" checked={config.plannerEnabled} onChange={v => set('plannerEnabled', v)} />
            </div>
            <div className="setting-row">
              <div>
                <div className="setting-title">Fixer</div>
                <div className="setting-desc">Writes the patch and self-tests it, up to three attempts.</div>
              </div>
              <Toggle label="Fixer" checked={config.fixerEnabled} onChange={v => set('fixerEnabled', v)} />
            </div>
          </motion.div>

          <motion.div variants={itemVariants} className="card">
            <h2 className="section-title"><ShieldCheck size={16} /> Safety</h2>
            <div className="setting-row">
              <div>
                <div className="setting-title">Require guardrails</div>
                <div className="setting-desc">Reject any patch that touches a file outside the plan's allow-list.</div>
              </div>
              <Toggle label="Require guardrails" checked={config.requireGuardrails} onChange={v => set('requireGuardrails', v)} />
            </div>
            <div className="setting-row">
              <div>
                <div className="setting-title">Auto-commit</div>
                <div className="setting-desc">Commit directly instead of opening a pull request for review.</div>
              </div>
              <Toggle label="Auto-commit" checked={config.autoCommit} onChange={v => set('autoCommit', v)} />
            </div>
            {config.autoCommit && (
              <div style={{ marginTop: 14 }}>
                <Alert tone="warning">Changes would land without a human looking at them first.</Alert>
              </div>
            )}
          </motion.div>
        </div>
      </div>
    </motion.div>
  );
}
