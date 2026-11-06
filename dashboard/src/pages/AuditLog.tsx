import { useQuery } from '@tanstack/react-query';
import { ScrollText } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

interface AuditEntry {
  action: string;
  actor: string | null;
  target: string | null;
  details: string | null;
  createdAt: string;
}

const ACTION_LABEL: Record<string, string> = {
  'organization.created': 'Created the organisation',
  'organization.renamed': 'Renamed the organisation',
  'invitation.created': 'Invited',
  'invitation.revoked': 'Cancelled the invitation for',
  'invitation.accepted': 'Accepted an invitation',
  'member.role_changed': 'Changed the role of',
  'member.removed': 'Removed',
  'member.migrated': 'Made an owner during the upgrade',
  'api_key.created': 'Created API key',
  'api_key.revoked': 'Revoked API key',
  'repo.allowed': 'Allowed repository',
  'repo.removed': 'Removed repository',
  'github.connected': 'Connected GitHub installation',
  'github.disconnected': 'Disconnected GitHub installation',
  'limits.changed': 'Changed the monthly limits of',
};

export default function AuditLog() {
  const { canManageOrg } = useAuth();

  const entries = useQuery({
    queryKey: ['org-audit'],
    queryFn: async () => (await apiClient.get('/org/audit', { params: { limit: 200 } })).data as AuditEntry[],
    enabled: canManageOrg,
  });

  if (!canManageOrg) {
    return <EmptyState icon={<ScrollText size={20} />} title="Admins only" description="Ask an admin of this organisation." />;
  }

  return (
    <>
      <PageHeader title="Audit log" description="Who changed access in this organisation, most recent first. Entries can't be edited or deleted." />

      {entries.isLoading && <LoadingState />}
      {entries.isError && <Alert tone="danger">Couldn't load the audit log.</Alert>}
      {entries.data?.length === 0 && <EmptyState icon={<ScrollText size={20} />} title="Nothing yet" />}
      {!!entries.data?.length && (
        <div className="table-container">
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>When</th><th>Who</th><th>What</th><th>Details</th></tr></thead>
              <tbody>
                {entries.data.map((entry, index) => (
                  <tr key={`${entry.createdAt}-${index}`}>
                    <td>{new Date(entry.createdAt).toLocaleString()}</td>
                    <td className="cell-primary">{entry.actor ?? 'System or API key'}</td>
                    <td>{ACTION_LABEL[entry.action] ?? entry.action} {entry.target && <code className="inline">{entry.target}</code>}</td>
                    <td>{entry.details ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
