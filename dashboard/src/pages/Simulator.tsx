import { useState } from 'react';
import { motion } from 'framer-motion';
import { Play, RotateCcw, CheckCircle2, XCircle, Loader2, Webhook } from 'lucide-react';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { PageHeader, Alert, EmptyState } from '../components/ui';

/** The webhook endpoints live at the gateway root (/webhook/**), not under the
 *  /api/v1 prefix apiClient is based at — so derive the gateway origin from the
 *  same configured base URL instead of hardcoding one. */
const WEBHOOK_URL = `${(apiClient.defaults.baseURL || '').replace(/\/api\/v1\/?$/, '')}/webhook/github`;

const defaultPayload = `{
  "action": "completed",
  "repository": {
    "full_name": "bettercallok/git-oracle"
  },
  "workflow_run": {
    "status": "completed",
    "conclusion": "failure"
  }
}`;

export default function Simulator() {
  const [payload, setPayload] = useState(defaultPayload);
  const [isLoading, setIsLoading] = useState(false);
  const [response, setResponse] = useState<{ status: number; data: unknown; ms: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  let jsonError: string | null = null;
  try { JSON.parse(payload); } catch (e) { jsonError = (e as Error).message; }

  const handleTrigger = async () => {
    setIsLoading(true);
    setResponse(null);
    setError(null);
    const t0 = performance.now();

    try {
      const parsed = JSON.parse(payload);
      // Go through apiClient rather than a bare fetch: the gateway's
      // TenantContextFilter requires X-API-Key on every non-exempt path, and
      // /webhook/** is not exempt. The raw fetch sent no key, so every click
      // silently 401'd and the button appeared to do nothing at all.
      const res = await apiClient.post(WEBHOOK_URL, parsed);
      setResponse({ status: res.status, data: res.data, ms: performance.now() - t0 });
    } catch (err: any) {
      if (err instanceof SyntaxError) {
        setError('The payload isn\'t valid JSON.');
      } else if (err.response) {
        // Surface the server's actual status/body instead of swallowing it —
        // an auth or routing failure should be visible in the Response panel.
        setResponse({ status: err.response.status, data: err.response.data, ms: performance.now() - t0 });
      } else {
        setError(err.message || 'Couldn\'t reach the API gateway.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const ok = response && response.status >= 200 && response.status < 300;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Webhook simulator"
        description="Send a GitHub webhook to the error ingestor by hand — useful locally, where GitHub can't reach you."
      />

      <motion.div variants={itemVariants} className="grid-sidebar">
        <div className="card">
          <div className="card-header">
            <div>
              <div className="card-title">Payload</div>
              <div className="card-subtitle mono" style={{ wordBreak: 'break-all' }}>POST {WEBHOOK_URL}</div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={() => setPayload(defaultPayload)} disabled={payload === defaultPayload}>
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
              {isLoading ? <><Loader2 size={15} className="spinner" /> Sending…</> : <><Play size={15} /> Send webhook</>}
            </button>
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <div className="card-title">Response</div>
            {response && (
              <span className={`badge ${ok ? 'badge-success' : 'badge-danger'}`}>
                {response.status} · {Math.round(response.ms)} ms
              </span>
            )}
          </div>

          {!response && !error && !isLoading && (
            <EmptyState icon={<Webhook size={20} />} title="Nothing sent yet" description="The gateway's reply will appear here." />
          )}
          {isLoading && <EmptyState icon={<Loader2 size={20} className="spinner" />} title="Waiting for the gateway…" />}
          {error && <Alert tone="danger" title="Request failed">{error}</Alert>}
          {response && (
            <motion.pre initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} className="code-block" style={{ maxHeight: 360 }}>
              {typeof response.data === 'object' ? JSON.stringify(response.data, null, 2) : String(response.data ?? '')}
            </motion.pre>
          )}
        </div>
      </motion.div>
    </motion.div>
  );
}
