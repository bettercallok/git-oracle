import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users } from 'lucide-react';
import { apiClient } from '../api/client';
import { useAuth, ROLE_LABEL, ROLES, type OrgRole } from '../context/AuthContext';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';

interface Member {
  userId: string;
  githubLogin: string | null;
  role: OrgRole;
  joinedAt: string | null;
}

interface Usage {
  jobs: number;
  jobLimit: number | null;
  tokens: number;
  tokenLimit: number | null;
}

interface Invitation {
  id: string;
  githubLogin: string;
  role: OrgRole;
  expiresAt: string;
}

const errorMessage = (error: unknown): string => {
  const data = (error as { response?: { data?: { error?: string; message?: string; errors?: Record<string, string>; detail?: string } } }).response?.data;
  const fieldError = data?.errors ? Object.values(data.errors)[0] : undefined;
  return data?.error && data.error !== 'Forbidden' ? data.error : fieldError ?? data?.detail ?? data?.message ?? 'Something went wrong. Please try again.';
};

const formatDate = (raw: string | null): string =>
  raw ? new Date(raw).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '—';

export default function Members() {
  const { user, role: ownRole, activeOrganization, canManageOrg, isPlatformAdmin } = useAuth();
  const queryClient = useQueryClient();
  const [login, setLogin] = useState('');
  const [inviteRole, setInviteRole] = useState<OrgRole>('MEMBER');
  const [name, setName] = useState<string | null>(null);

  // What this person may grant: nobody grants above their own role. The server
  // enforces this too; here it just avoids offering choices that would be refused.
  const grantable = ROLES.filter(role => isPlatformAdmin || (ownRole && ROLES.indexOf(role) <= ROLES.indexOf(ownRole)));
  const mayChange = (target: OrgRole) => canManageOrg && grantable.includes(target);

  const members = useQuery({
    queryKey: ['org-members'],
    queryFn: async () => (await apiClient.get('/org/members')).data as Member[],
  });

  const usage = useQuery({
    queryKey: ['org-usage'],
    queryFn: async () => (await apiClient.get('/org/usage')).data as Usage,
  });

  const invitations = useQuery({
    queryKey: ['org-invitations'],
    queryFn: async () => (await apiClient.get('/org/invitations')).data as Invitation[],
    enabled: canManageOrg,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['org-members'] });
    queryClient.invalidateQueries({ queryKey: ['org-invitations'] });
  };

  const invite = useMutation({
    mutationFn: async () => apiClient.post('/org/invitations', { githubLogin: login.trim(), role: inviteRole }),
    onSuccess: () => { setLogin(''); refresh(); },
  });
  const revokeInvitation = useMutation({
    mutationFn: async (id: string) => apiClient.delete(`/org/invitations/${id}`),
    onSuccess: refresh,
  });
  const changeRole = useMutation({
    mutationFn: async ({ userId, role }: { userId: string; role: OrgRole }) => apiClient.put(`/org/members/${userId}/role`, { role }),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: async (userId: string) => apiClient.delete(`/org/members/${userId}`),
    onSuccess: refresh,
  });
  const rename = useMutation({
    mutationFn: async (newName: string) => apiClient.put('/org', { name: newName }),
    // A full reload, because the sidebar's organisation name comes from sign-in.
    onSuccess: () => { window.location.reload(); },
  });

  const failure = invite.error ?? revokeInvitation.error ?? changeRole.error ?? remove.error ?? rename.error;

  const confirmChange = (member: Member, role: OrgRole) => {
    const who = member.githubLogin ?? 'this person';
    const note = member.userId === user?.id ? 'You' : `${who}`;
    const extra = role === 'VIEWER' ? ' API keys they made will stop working.' : '';
    if (window.confirm(`Make ${who} ${ROLE_LABEL[role]}? ${note} will need to sign in again.${extra}`)) {
      changeRole.mutate({ userId: member.userId, role });
    }
  };

  const confirmRemove = (member: Member) => {
    if (window.confirm(`Remove ${member.githubLogin ?? 'this person'}? They lose access right away, and API keys they made stop working.`)) {
      remove.mutate(member.userId);
    }
  };

  return (
    <>
      <PageHeader title="Members" description={`Who can use ${activeOrganization?.name ?? 'this organisation'}, and what they can do.`} />

      {failure && <div style={{ marginBottom: 16 }}><Alert tone="danger">{errorMessage(failure)}</Alert></div>}

      {usage.data && (
        <div className="usage-grid">
          <div className="card usage-card">
            <div className="status-label">Jobs this month</div>
            <div className="usage-value">{usage.data.jobs.toLocaleString()}{usage.data.jobLimit !== null && ` of ${usage.data.jobLimit.toLocaleString()}`}</div>
            {usage.data.jobLimit === null && <div className="status-hint">No limit</div>}
          </div>
          <div className="card usage-card">
            <div className="status-label">LLM tokens this month</div>
            <div className="usage-value">{usage.data.tokens.toLocaleString()}{usage.data.tokenLimit !== null && ` of ${usage.data.tokenLimit.toLocaleString()}`}</div>
            {usage.data.tokenLimit === null && <div className="status-hint">No limit</div>}
          </div>
        </div>
      )}

      {canManageOrg && (
        <form
          className="card api-key-form"
          onSubmit={e => { e.preventDefault(); if (login.trim()) invite.mutate(); }}
        >
          <div className="api-key-field-name">
            <label className="form-label" htmlFor="invite-login">Invite by GitHub username</label>
            <input
              id="invite-login"
              className="form-input"
              placeholder="octocat"
              autoComplete="off"
              spellCheck={false}
              value={login}
              onChange={e => setLogin(e.target.value)}
            />
          </div>
          <div>
            <label className="form-label" htmlFor="invite-role">Role</label>
            <select id="invite-role" className="form-select" value={inviteRole} onChange={e => setInviteRole(e.target.value as OrgRole)}>
              {grantable.map(role => <option key={role} value={role}>{ROLE_LABEL[role]}</option>)}
            </select>
          </div>
          <button type="submit" className="btn btn-primary" disabled={!login.trim() || invite.isPending}>
            {invite.isPending ? 'Inviting…' : 'Invite'}
          </button>
        </form>
      )}

      {members.isLoading && <LoadingState />}
      {members.isError && <Alert tone="danger">{errorMessage(members.error)}</Alert>}
      {members.data?.length === 0 && (
        <EmptyState icon={<Users size={20} />} title="No members yet" description="Invite someone above." />
      )}
      {!!members.data?.length && (
        <div className="table-container" style={{ marginBottom: 16 }}>
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>Member</th><th>Role</th><th>Joined</th><th /></tr></thead>
              <tbody>
                {members.data.map(member => (
                  <tr key={member.userId}>
                    <td className="cell-primary">
                      {member.githubLogin ?? member.userId}
                      {member.userId === user?.id && <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}> (you)</span>}
                    </td>
                    <td>
                      {mayChange(member.role) ? (
                        <select
                          className="form-select"
                          aria-label={`Role of ${member.githubLogin}`}
                          value={member.role}
                          disabled={changeRole.isPending}
                          onChange={e => confirmChange(member, e.target.value as OrgRole)}
                        >
                          {grantable.map(role => <option key={role} value={role}>{ROLE_LABEL[role]}</option>)}
                        </select>
                      ) : ROLE_LABEL[member.role]}
                    </td>
                    <td>{formatDate(member.joinedAt)}</td>
                    <td className="num">
                      {mayChange(member.role) && (
                        <button type="button" className="btn btn-ghost btn-sm" disabled={remove.isPending} onClick={() => confirmRemove(member)}>
                          Remove
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {canManageOrg && !!invitations.data?.length && (
        <div className="table-container" style={{ marginBottom: 16 }}>
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>Invited</th><th>Role</th><th>Expires</th><th /></tr></thead>
              <tbody>
                {invitations.data.map(invitation => (
                  <tr key={invitation.id}>
                    <td className="cell-primary">{invitation.githubLogin}</td>
                    <td>{ROLE_LABEL[invitation.role]}</td>
                    <td>{formatDate(invitation.expiresAt)}</td>
                    <td className="num">
                      {mayChange(invitation.role) && (
                        <button type="button" className="btn btn-ghost btn-sm" disabled={revokeInvitation.isPending} onClick={() => revokeInvitation.mutate(invitation.id)}>
                          Cancel
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {canManageOrg && (
        <form
          className="card api-key-form"
          onSubmit={e => { e.preventDefault(); if (name?.trim()) rename.mutate(name.trim()); }}
        >
          <div className="api-key-field-name">
            <label className="form-label" htmlFor="org-name">Organisation name</label>
            <input
              id="org-name"
              className="form-input"
              maxLength={100}
              value={name ?? activeOrganization?.name ?? ''}
              onChange={e => setName(e.target.value)}
            />
          </div>
          <button type="submit" className="btn btn-secondary" disabled={!name?.trim() || name.trim() === activeOrganization?.name || rename.isPending}>
            Rename
          </button>
        </form>
      )}

      <p className="status-hint" style={{ marginTop: 16 }}>
        Viewers can look but not change anything. Members can start jobs and create API keys. Admins also manage people. Only owners can make or change owners.
      </p>
    </>
  );
}
