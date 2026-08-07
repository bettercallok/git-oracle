import { useState } from 'react';
import { TerminalSquare, Play, RefreshCw, CheckCircle2, XCircle } from 'lucide-react';
import { motion } from 'framer-motion';
import { apiClient } from '../api/client';

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
  const [response, setResponse] = useState<{ status: number, data: any } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleTrigger = async () => {
    setIsLoading(true);
    setResponse(null);
    setError(null);
    
    try {
      // Validate JSON first
      const parsed = JSON.parse(payload);

      // Go through apiClient rather than a bare fetch: the gateway's
      // TenantContextFilter requires X-API-Key on every non-exempt path, and
      // /webhook/** is not exempt. The raw fetch sent no key, so every click
      // silently 401'd and the button appeared to do nothing at all.
      const res = await apiClient.post(WEBHOOK_URL, parsed);
      setResponse({ status: res.status, data: res.data });
    } catch (err: any) {
      if (err instanceof SyntaxError) {
        setError('Invalid JSON payload');
      } else if (err.response) {
        // Surface the server's actual status/body instead of swallowing it —
        // an auth or routing failure should be visible in the Response panel.
        setResponse({ status: err.response.status, data: err.response.data });
      } else {
        setError(err.message || 'Failed to connect to API Gateway');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="page-container">
      <header className="page-header">
        <div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <TerminalSquare color="var(--accent)" /> Webhook Simulator
          </h1>
          <p className="page-description">Trigger a mock GitHub event to initiate the end-to-end multi-agent workflow.</p>
        </div>
      </header>
      
      <div className="grid" style={{ gridTemplateColumns: '2fr 1fr', gap: 24 }}>
        <div className="card">
          <div className="card-header">
            <h3>GitHub Payload</h3>
            <button 
              className="btn btn-secondary" 
              onClick={() => setPayload(defaultPayload)}
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
              </motion.div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
