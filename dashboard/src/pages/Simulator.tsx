import { useState } from 'react';
import { TerminalSquare, Play, RefreshCw, CheckCircle2, XCircle } from 'lucide-react';
import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useRepos } from '../context/RepoContext';
import { Alert, EmptyState } from '../components/ui';

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
  const { user } = useAuth();
  const { activeRepo } = useRepos();
  const defaultRepo = activeRepo?.fullName ?? '<owner>/<repo>';
  const [payload, setPayload] = useState(() => buildPayload(defaultRepo));
  const [isLoading, setIsLoading] = useState(false);
  const [response, setResponse] = useState<{ status: number, data: any } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleTrigger = async () => {
    setIsLoading(true);
    setResponse(null);
    setError(null);

    try {
      const parsed = JSON.parse(payload);

      // The server authenticates this as an admin and hands it to the same code a
      // signed webhook reaches. The browser can't sign it, because that would mean
      // giving it the webhook secret.
      const res = await apiClient.post('/admin/webhook-simulator', parsed);
      setResponse({ status: res.status, data: res.data });
      setPayload(buildPayload(parsed?.repository?.full_name ?? defaultRepo));
    } catch (err: any) {
      if (err instanceof SyntaxError) {
        setError('Invalid JSON payload');
      } else if (err.response) {
        setResponse({ status: err.response.status, data: err.response.data });
      } else {
        setError(err.message || 'Failed to connect to API Gateway');
      }
    } finally {
      setIsLoading(false);
    }
  };

  if (user && !user.admin) {
    return (
      <EmptyState
        icon={<TerminalSquare size={20} />}
        title="Admins only"
        description="Use Ask to fix to start a job on your repositories."
      />
    );
  }

  return (
    <div className="page-container">
      <header className="page-header">
        <div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <TerminalSquare color="var(--accent)" /> Webhook Simulator
          </h1>
          <p className="page-description">Simulate a failed GitHub Actions run to start the full pipeline without waiting for a real one.</p>
        </div>
      </header>
      
      <div style={{ marginBottom: 24 }}>
        <Alert tone="warning" title="This starts a real job">
          It uses your LLM provider and can open a real pull request on the repository in the payload, which must be on your allow-list.
        </Alert>
      </div>

      <div className="grid" style={{ gridTemplateColumns: '2fr 1fr', gap: 24 }}>
        <div className="card">
          <div className="card-header">
            <h3>GitHub Payload</h3>
            <button 
              className="btn btn-secondary" 
              onClick={() => setPayload(buildPayload(defaultRepo))}
              style={{ padding: '6px 12px', fontSize: '0.85rem' }}
            >
              <RefreshCw size={14} style={{ marginRight: 6 }} /> Reset
            </button>
          </div>
          
          <div style={{ marginTop: 16 }}>
            <textarea
              style={{
                width: '100%',
                height: 300,
                background: 'var(--bg-darker)',
                border: '1px solid var(--border)',
                borderRadius: 8,
                padding: 16,
                fontFamily: 'monospace',
                fontSize: '0.9rem',
                color: 'var(--text)',
                resize: 'vertical',
                outline: 'none'
              }}
              value={payload}
              onChange={(e) => setPayload(e.target.value)}
              spellCheck={false}
            />
          </div>
          
          <div style={{ marginTop: 24, display: 'flex', justifyContent: 'flex-end' }}>
            <button 
              className="btn btn-primary"
              onClick={handleTrigger}
              disabled={isLoading}
              style={{ padding: '12px 24px' }}
            >
              {isLoading ? (
                <>
                  <motion.div
                    animate={{ rotate: 360 }}
                    transition={{ repeat: Infinity, duration: 1, ease: 'linear' }}
                    style={{ display: 'flex' }}
                  >
                    <RefreshCw size={18} />
                  </motion.div>
                  Sending...
                </>
              ) : (
                <>
                  <Play size={18} /> Trigger Webhook
                </>
              )}
            </button>
          </div>
        </div>
        
        <div className="card" style={{ height: 'fit-content' }}>
          <div className="card-header">
            <h3>Response</h3>
          </div>
          
          <div style={{ marginTop: 16 }}>
            {!response && !error && !isLoading && (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }}>
                Waiting for request...
              </div>
            )}
            
            {isLoading && (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }}>
                <motion.div
                  animate={{ rotate: 360 }}
                  transition={{ repeat: Infinity, duration: 1, ease: 'linear' }}
                  style={{ display: 'inline-block', marginBottom: 12 }}
                >
                  <RefreshCw size={24} color="var(--accent)" />
                </motion.div>
                <p>Waiting for API Gateway...</p>
              </div>
            )}
            
            {error && (
              <div style={{ padding: 16, background: 'rgba(239, 68, 68, 0.1)', borderRadius: 8, border: '1px solid rgba(239, 68, 68, 0.2)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#ef4444', marginBottom: 8, fontWeight: 500 }}>
                  <XCircle size={18} /> Error
                </div>
                <div style={{ fontSize: '0.9rem', color: 'var(--text)' }}>
                  {error}
                </div>
              </div>
            )}
            
            {response && (
              <motion.div 
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                style={{ padding: 16, background: 'var(--bg-darker)', borderRadius: 8, border: '1px solid var(--border)' }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: response.status >= 200 && response.status < 300 ? '#10b981' : '#ef4444', marginBottom: 12, fontWeight: 500 }}>
                  {response.status >= 200 && response.status < 300 ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
                  Status: {response.status}
                </div>
                <pre style={{ 
                  margin: 0, 
                  fontSize: '0.85rem', 
                  fontFamily: 'monospace', 
                  color: 'var(--text)', 
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all' 
                }}>
                  {typeof response.data === 'object' ? JSON.stringify(response.data, null, 2) : response.data}
                </pre>
                {response.status === 202 && (
                  <p style={{ margin: '12px 0 0', fontSize: '0.85rem' }}>
                    <Link to="/">Follow it on the Jobs page</Link>.
                  </p>
                )}
              </motion.div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
