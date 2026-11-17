import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Play, RotateCcw, CheckCircle2, XCircle, Loader2, Webhook, TerminalSquare } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useRepos } from '../context/RepoContext';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader, Alert, EmptyState } from '../components/ui';

const buildPayload = (repo: string) => JSON.stringify({
  action: 'completed',
  repository: { full_name: repo },
  workflow_run: {
    // A job is created once per run id, so each new payload needs its own.
    id: Date.now(),
    status: 'completed',
    conclusion: 'failure',
  },
}, null, 2);

export default function Simulator() {
  const { isPlatformAdmin } = useAuth();
  const { activeRepo } = useRepos();
  const defaultRepo = activeRepo?.fullName ?? '<owner>/<repo>';
  const [payload, setPayload] = useState(() => buildPayload(defaultRepo));
  const [isLoading, setIsLoading] = useState(false);
  const [response, setResponse] = useState<{ status: number; data: unknown; ms: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // The repositories arrive from the server after this page first renders.
  useEffect(() => setPayload(buildPayload(defaultRepo)), [defaultRepo]);

  let jsonError: string | null = null;
  try { JSON.parse(payload); } catch (e) { jsonError = (e as Error).message; }

  const handleTrigger = async () => {
    setIsLoading(true);
    setResponse(null);
    setError(null);
    const t0 = performance.now();

    try {
      const parsed = JSON.parse(payload);

      // The server authenticates this as an admin and hands it to the same code a
      // signed webhook reaches. The browser can't sign it, because that would mean
      // giving it the webhook secret.
      const res = await apiClient.post('/admin/webhook-simulator', parsed);
      setResponse({ status: res.status, data: res.data, ms: performance.now() - t0 });
      setPayload(buildPayload(parsed?.repository?.full_name ?? defaultRepo));
    } catch (err: any) {
      if (err instanceof SyntaxError) {
        setError('The payload isn\'t valid JSON.');
      } else if (err.response) {
        setResponse({ status: err.response.status, data: err.response.data, ms: performance.now() - t0 });
      } else {
        setError(err.message || 'Couldn\'t reach the API gateway.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  if (!isPlatformAdmin) {
    return (
      <EmptyState
        icon={<TerminalSquare size={20} />}
        title="Admins only"
        description="Use Ask to fix to start a job on your repositories."
      />
    );
  }

  const ok = response && response.status >= 200 && response.status < 300;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Webhook simulator"
        description="Simulate a failed GitHub Actions run to start the full pipeline without waiting for a real one."
      />

      <motion.div variants={itemVariants} style={{ marginBottom: 16 }}>
        <Alert tone="warning" title="This starts a real job">
          It uses your LLM provider and can open a real pull request on the repository in the payload, which must be on your allow-list.
        </Alert>
      </motion.div>

      <motion.div variants={itemVariants} className="grid-sidebar">
        <div className="card">
          <div className="card-header">
            <div>
              <div className="card-title">Payload</div>
              <div className="card-subtitle">A failed <span className="mono">workflow_run</span>, sent as an admin rather than signed by GitHub</div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={() => setPayload(buildPayload(defaultRepo))}>
              <RotateCcw size={13} /> Reset
            </button>
          </div>

          <textarea
            className="code-block"
            style={{ height: 320 }}
            value={payload}
            onChange={(e) => setPayload(e.target.value)}
            spellCheck={false}
            aria-label="Webhook JSON payload"
          />

          <div style={{ marginTop: 14, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <span style={{ fontSize: '0.8rem', color: jsonError ? 'var(--danger)' : 'var(--text-muted)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {jsonError ? <><XCircle size={13} /> Invalid JSON</> : <><CheckCircle2 size={13} color="var(--success)" /> Valid JSON</>}
            </span>
            <button className="btn btn-primary" onClick={handleTrigger} disabled={isLoading || !!jsonError}>
              {isLoading ? <><Loader2 size={15} className="spinner" /> Sending…</> : <><Play size={15} /> Trigger webhook</>}
            </button>
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <div className="card-title">Response</div>
            {response && (
              <span className={`badge ${ok ? 'badge-success' : 'badge-danger'}`}>
                Status: {response.status} · {Math.round(response.ms)} ms
              </span>
            )}
          </div>

          {!response && !error && !isLoading && (
            <EmptyState icon={<Webhook size={20} />} title="Nothing sent yet" description="The reply will appear here." />
          )}
          {isLoading && <EmptyState icon={<Loader2 size={20} className="spinner" />} title="Waiting for the gateway…" />}
          {error && <Alert tone="danger" title="Request failed">{error}</Alert>}
          {response && (
            <motion.pre initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} className="code-block" style={{ maxHeight: 360 }}>
              {typeof response.data === 'object' ? JSON.stringify(response.data, null, 2) : String(response.data ?? '')}
            </motion.pre>
          )}
          {response?.status === 202 && (
            <p style={{ margin: '12px 0 0', fontSize: '0.85rem' }}>
              <Link to="/" className="link">Follow it on the Jobs page</Link>.
            </p>
          )}
        </div>
      </motion.div>
    </motion.div>
  );
}
