import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Copy, KeyRound } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

interface ApiKeyRow {
  keyPrefix: string;
  name: string;
  createdAt: string | null;
  lastUsedAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
}

interface CreatedKey {
  apiKey: string;
  name: string;
  expiresAt: string;
}

const EXPIRY_OPTIONS = [
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 365, label: '1 year' },
];

const formatDate = (raw: string | null): string =>
  raw ? new Date(raw).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '—';

function statusOf(key: ApiKeyRow, now: number): 'Active' | 'Revoked' | 'Expired' {
  if (key.revokedAt) return 'Revoked';
  if (key.expiresAt && new Date(key.expiresAt).getTime() <= now) return 'Expired';
  return 'Active';
}

const errorMessage = (error: unknown): string => {
  const data = (error as { response?: { data?: { error?: string; message?: string } } }).response?.data;
  return data?.error ?? data?.message ?? 'Something went wrong. Please try again.';
};

function NewKeyNotice({ created, onDone }: { created: CreatedKey; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(created.apiKey);
    setCopied(true);
  };

  return (
    <Alert tone="success" title={`Copy your key for “${created.name}” now`} icon={<KeyRound size={16} />}>
      <p style={{ margin: '4px 0 10px' }}>This is the only time it's shown. Keep it out of your code and out of chat.</p>
      <pre className="code-block" style={{ margin: '0 0 10px' }}>{created.apiKey}</pre>
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" className="btn btn-secondary btn-sm" onClick={copy}>
          {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? 'Copied' : 'Copy key'}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onDone}>Done</button>
      </div>
    </Alert>
  );
}

export default function ApiKeys() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [days, setDays] = useState(90);
  const [created, setCreated] = useState<CreatedKey | null>(null);
  const [now] = useState(() => Date.now());

  const keys = useQuery({
    queryKey: ['api-keys'],
    queryFn: async () => (await apiClient.get('/keys')).data as ApiKeyRow[],
    enabled: !!user,
  });

  const create = useMutation({
    mutationFn: async () => (await apiClient.post('/keys', { name: name.trim(), expiresInDays: days })).data as CreatedKey,
    onSuccess: (data) => {
      setCreated(data);
      setName('');
      queryClient.invalidateQueries({ queryKey: ['api-keys'] });
    },
  });

  const revoke = useMutation({
    mutationFn: async (keyPrefix: string) => apiClient.delete(`/keys/${keyPrefix}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['api-keys'] }),
  });

  if (!user) {
    return (
      <>
        <PageHeader title="API keys" />
        <EmptyState
          icon={<KeyRound size={20} />}
          title="Sign in with GitHub to manage keys"
          description="You're signed in with an API key, and a key can't create or revoke other keys."
        />
      </>
    );
  }

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (name.trim()) create.mutate();
  };

  const confirmRevoke = (key: ApiKeyRow) => {
    if (window.confirm(`Revoke “${key.name}”? Anything using it stops working within a minute. This can't be undone.`)) {
      revoke.mutate(key.keyPrefix);
    }
  };

  return (
    <>
      <PageHeader
        title="API keys"
        description="For scripts and CI to call GitOracle. You don't need one to use the dashboard."
      />

      {created && <div style={{ marginBottom: 16 }}><NewKeyNotice created={created} onDone={() => setCreated(null)} /></div>}

      <form className="card api-key-form" onSubmit={submit}>
        <div className="api-key-field-name">
          <label className="form-label" htmlFor="api-key-name">Name</label>
          <input
            id="api-key-name"
            className="form-input"
            placeholder="e.g. GitHub Actions"
            maxLength={100}
            value={name}
            onChange={e => setName(e.target.value)}
          />
        </div>
        <div>
          <label className="form-label" htmlFor="api-key-expiry">Expires after</label>
          <select id="api-key-expiry" className="form-select" value={days} onChange={e => setDays(Number(e.target.value))}>
            {EXPIRY_OPTIONS.map(option => <option key={option.days} value={option.days}>{option.label}</option>)}
          </select>
        </div>
        <button type="submit" className="btn btn-primary" disabled={!name.trim() || create.isPending}>
          {create.isPending ? 'Creating…' : 'Create key'}
        </button>
      </form>

      {create.isError && <div style={{ marginBottom: 16 }}><Alert tone="danger">{errorMessage(create.error)}</Alert></div>}
      {revoke.isError && <div style={{ marginBottom: 16 }}><Alert tone="danger">{errorMessage(revoke.error)}</Alert></div>}

      {keys.isLoading && <LoadingState />}
      {keys.isError && <Alert tone="danger">{errorMessage(keys.error)}</Alert>}
      {keys.data?.length === 0 && (
        <EmptyState icon={<KeyRound size={20} />} title="No keys yet" description="Create one above when a script needs access." />
      )}
      {!!keys.data?.length && (
        <div className="table-container">
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr><th>Name</th><th>Key</th><th>Status</th><th>Created</th><th>Last used</th><th>Expires</th><th /></tr>
              </thead>
              <tbody>
                {keys.data.map(key => {
                  const status = statusOf(key, now);
                  return (
                    <tr key={key.keyPrefix}>
                      <td className="cell-primary">{key.name}</td>
                      <td className="mono">gor_{key.keyPrefix}…</td>
                      <td>{status}</td>
                      <td>{formatDate(key.createdAt)}</td>
                      <td>{formatDate(key.lastUsedAt)}</td>
                      <td>{key.expiresAt ? formatDate(key.expiresAt) : 'Never'}</td>
                      <td className="num">
                        {status === 'Active' && (
                          <button type="button" className="btn btn-ghost btn-sm" disabled={revoke.isPending} onClick={() => confirmRevoke(key)}>
                            Revoke
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
