import { useQuery } from '@tanstack/react-query';
import { Activity, Cpu, ShieldCheck } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

interface Endpoint {
  host: string;
  local: boolean;
}

interface RuntimeConfig {
  llm: { endpoint: Endpoint | null; model: string | null; sendsApiKey: boolean };
  fallback: { endpoint: Endpoint | null; model: string | null } | null;
  embeddings: { endpoint: Endpoint | null; model: string | null } | null;
  kafka: { host: string; authenticated: boolean };
  qdrant: { host: string };
}

interface ServiceStatus {
  name: string;
  kind: 'java' | 'agent';
  status: 'up' | 'degraded' | 'down';
  detail: string | null;
}

interface SystemStatusResponse {
  services: ServiceStatus[];
  config: RuntimeConfig | null;
  configConsistent: boolean;
}

const STATUS_LABEL: Record<ServiceStatus['status'], string> = { up: 'Running', degraded: 'Unhealthy', down: 'Down' };

function Row({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="status-row">
      <div className="status-label">{label}</div>
      <div>
        <div className="status-value mono">{value}</div>
        {hint && <div className="status-hint">{hint}</div>}
      </div>
    </div>
  );
}

const where = (endpoint: Endpoint | null | undefined) =>
  !endpoint ? undefined : endpoint.local ? 'Stays on your own machine or network' : 'External service: code is sent here';

function ServiceTable({ title, services }: { title: string; services: ServiceStatus[] }) {
  return (
    <div className="table-container" style={{ marginBottom: 16 }}>
      <div className="table-scroll">
        <table className="table">
          <thead><tr><th>{title}</th><th>Status</th><th>Details</th></tr></thead>
          <tbody>
            {services.map(service => (
              <tr key={service.name}>
                <td className="cell-primary">{service.name}</td>
                <td><span className={`status-pill ${service.status}`}>{STATUS_LABEL[service.status]}</span></td>
                <td>{service.detail ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function SystemStatus() {
  const { user } = useAuth();

  const { data, isLoading, isError } = useQuery({
    queryKey: ['system-status'],
    queryFn: async () => (await apiClient.get('/admin/system/status')).data as SystemStatusResponse,
    refetchInterval: 15_000,
    enabled: !user || user.admin,
  });

  if (user && !user.admin) {
    return (
      <EmptyState
        icon={<Activity size={20} />}
        title="Admins only"
        description="This page shows how the whole system is set up."
      />
    );
  }

  const config = data?.config;
  const fallbackLeavesNetwork = !!config?.fallback?.endpoint && !config.fallback.endpoint.local;
  const down = data?.services.filter(service => service.status !== 'up') ?? [];

  return (
    <>
      <PageHeader
        title="System status"
        description="What's running right now. To change a setting, edit .env and restart; it can't be changed here, so a stolen session can't redirect your code to another server."
      />

      {isLoading && <LoadingState />}
      {isError && <Alert tone="danger">Couldn't load the system status. This page needs an admin account.</Alert>}

      {down.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <Alert tone="danger" title="Needs attention">
            {down.map(service => service.name).join(', ')} ({down.length} of {data?.services.length} services)
          </Alert>
        </div>
      )}
      {data && !data.configConsistent && (
        <div style={{ marginBottom: 16 }}>
          <Alert tone="warning" title="Agents disagree about their settings">
            Some agents are running with older settings, so the values below may not apply to all of them. Restart with{' '}
            <code className="inline">./stop_local.sh && ./start_local.sh</code>.
          </Alert>
        </div>
      )}
      {fallbackLeavesNetwork && (
        <div style={{ marginBottom: 16 }}>
          <Alert tone="warning" title="A cloud fallback is set">
            If the LLM fails, your code is sent to <code className="inline">{config?.fallback?.endpoint?.host}</code>.
          </Alert>
        </div>
      )}

      {data && (
        <div className="status-grid">
          <section className="card status-card">
            <h2 className="status-heading"><Cpu size={16} /> Configuration</h2>
            {!config && <p className="status-hint">No agent answered, so the settings in use are unknown.</p>}
            {config && <>
              <Row label="LLM" value={config.llm.endpoint?.host ?? 'Not set'} hint={where(config.llm.endpoint)} />
              <Row label="Model" value={config.llm.model ?? 'Not set'} />
              <Row label="LLM API key" value={config.llm.sendsApiKey ? 'Sent with every call' : 'None'} />
              <Row label="Fallback LLM" value={config.fallback?.endpoint?.host ?? 'None'} hint={where(config.fallback?.endpoint)} />
              <Row
                label="Agent memory"
                value={config.embeddings?.endpoint?.host ?? 'Off'}
                hint={config.embeddings ? where(config.embeddings.endpoint) : 'Set EMBEDDINGS_BASE_URL to turn it on'}
              />
              <Row label="Kafka" value={config.kafka.host} hint={config.kafka.authenticated ? 'Authenticated' : 'No authentication'} />
              <Row label="Qdrant" value={config.qdrant.host} />
            </>}
          </section>

          <section className="card status-card">
            <h2 className="status-heading"><ShieldCheck size={16} /> Always on</h2>
            <p className="status-rule"><strong>Guardrails.</strong> A patch may only touch the files in its plan. Anything else is rejected.</p>
            <p className="status-rule"><strong>Human review.</strong> Every fix ends in a pull request. GitOracle never merges by itself.</p>
            <p className="status-rule"><strong>Sandboxed tests.</strong> A repository's tests run in Docker. If Docker is down, tests stop.</p>
          </section>
        </div>
      )}

      {data && <>
        <ServiceTable title="Services" services={data.services.filter(service => service.kind === 'java')} />
        <ServiceTable title="Agents" services={data.services.filter(service => service.kind === 'agent')} />
      </>}
    </>
  );
}
