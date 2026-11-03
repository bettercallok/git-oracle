import { useState, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { GitCommitHorizontal, ChevronLeft, ChevronRight, Search, FolderGit2, RefreshCw, X, AlertCircle } from 'lucide-react';
import { useRepos } from '../context/RepoContext';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { errorMessage, relativeTime } from '../utils/format';
import { PageHeader, Alert, EmptyState, Avatar } from '../components/ui';

interface Commit {
  sha: string;
  shortSha: string;
  shortMessage: string;
  message: string;
  author: string;
  authorEmail: string;
  date: string | null;
  filesChanged: number;
  additions: number;
  deletions: number;
  totalChanges: number;
}

interface CommitsResponse {
  repo: string;
  page: number;
  perPage: number;
  count: number;
  commits: Commit[];
}

function absoluteDate(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

/** Five-block add/delete ratio, like GitHub's diffstat. */
function DiffBlocks({ add, del }: { add: number; del: number }) {
  const total = add + del;
  if (total === 0) return null;
  const greens = Math.round((add / total) * 5);
  return (
    <span style={{ display: 'inline-flex', gap: 1.5, marginLeft: 6 }} aria-hidden="true">
      {Array.from({ length: 5 }, (_, i) => (
        <i key={i} style={{
          width: 7, height: 7, borderRadius: 1.5, display: 'block',
          background: i < greens ? 'var(--success)' : 'var(--danger)',
          opacity: 0.85,
        }} />
      ))}
    </span>
  );
}

const PER_PAGE = 20;

export default function CommitExplorer() {
  const { activeRepo } = useRepos();
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');

  const repo = activeRepo?.fullName ?? null;

  const { data, isLoading, isError, error, refetch, isFetching } = useQuery<CommitsResponse>({
    queryKey: ['commits', repo, page],
    queryFn: async () => {
      const res = await apiClient.get('/commits', {
        params: { repo, page, per_page: PER_PAGE },
      });
      return res.data;
    },
    enabled: !!repo,
    staleTime: 60_000,
    placeholderData: (prev) => prev,
  });

  // Client-side filter by message, author or SHA
  const filtered = useMemo(() => {
    if (!data?.commits) return [];
    const q = search.toLowerCase().trim();
    if (!q) return data.commits;
    return data.commits.filter(c =>
      c.shortMessage.toLowerCase().includes(q) ||
      c.author.toLowerCase().includes(q) ||
      c.shortSha.includes(q)
    );
  }, [data, search]);

  const hasPrev = page > 1;
  const hasNext = (data?.count ?? 0) === PER_PAGE; // a full page means there's likely more

  const goTo = (p: number) => { setPage(p); window.scrollTo({ top: 0, behavior: 'smooth' }); };

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="Commits"
        description={repo
          ? <>Recent history of <span className="mono" style={{ color: 'var(--text-primary)' }}>{repo}</span>. Open a commit to read its diff or ask the analyst about it.</>
          : 'Browse a repository\'s history and ask questions about any commit.'}
        actions={repo && (
          <button className="btn" onClick={() => refetch()} disabled={isFetching}>
            <RefreshCw size={14} className={isFetching ? 'spinner' : undefined} /> Refresh
          </button>
        )}
      />

      {!repo && (
        <motion.div variants={itemVariants} className="card dashed">
          <EmptyState
            icon={<FolderGit2 size={20} />}
            title="No active repository"
            description="Pick a repository to browse its commits."
            action={<Link to="/repos" className="btn btn-primary">Choose a repository</Link>}
          />
        </motion.div>
      )}

      {repo && (
        <motion.div variants={itemVariants} className="table-container">
          <div className="table-toolbar">
            <div className="input-wrap" style={{ flex: 1, maxWidth: 380 }}>
              <Search size={14} className="input-icon" />
              <input
                id="commit-search"
                type="text"
                className="form-input"
                style={{ height: 32, fontSize: '0.86rem', paddingRight: 32 }}
                placeholder="Filter this page by message, author or SHA"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
              {search && (
                <button className="btn btn-ghost btn-sm btn-icon input-action" style={{ width: 24, height: 24 }}
                        onClick={() => setSearch('')} aria-label="Clear filter"><X size={13} /></button>
              )}
            </div>
            <span className="muted" style={{ fontSize: '0.8rem' }}>
              Page {page}{data ? ` · ${filtered.length} of ${data.count}` : ''}
            </span>
          </div>

          {isLoading && (
            <div>
              {Array.from({ length: 6 }, (_, i) => (
                <div key={i} style={{ display: 'flex', gap: 16, padding: '15px 18px', borderTop: i ? '1px solid var(--border-subtle)' : 'none' }}>
                  <div className="skeleton" style={{ width: 60, height: 14 }} />
                  <div className="skeleton" style={{ flex: 1, height: 14, maxWidth: 420 }} />
                  <div className="skeleton" style={{ width: 90, height: 14, marginLeft: 'auto' }} />
                </div>
              ))}
            </div>
          )}

          {isError && (
            <div style={{ padding: 16 }}>
              <Alert tone="danger" icon={<AlertCircle size={16} />} title="Couldn't load commits">
                {errorMessage(error, 'Check that the GitOracle GitHub App is installed on this repository.')}
              </Alert>
            </div>
          )}

          {!isLoading && !isError && filtered.length === 0 && (
            <EmptyState
              icon={<GitCommitHorizontal size={20} />}
              title={search ? 'No matching commits' : 'No commits found'}
              description={search ? 'The filter only searches this page.' : `GitHub returned no commits for ${repo}.`}
            />
          )}

          {!isLoading && !isError && filtered.length > 0 && (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Commit</th>
                    <th className="hide-mobile">Author</th>
                    <th className="num hide-mobile">Changes</th>
                    <th className="num">When</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map(commit => (
                    <tr key={commit.sha} className="clickable"
                        onClick={() => navigate(`/commits/${commit.sha}?repo=${encodeURIComponent(repo)}`)}>
                      <td style={{ maxWidth: 520 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                          <span className="mono" style={{ color: 'var(--accent)', flexShrink: 0 }}>{commit.shortSha}</span>
                          <span className="cell-primary truncate">{commit.shortMessage}</span>
                        </div>
                      </td>
                      <td className="hide-mobile">
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                          <Avatar name={commit.author} seed={commit.authorEmail} size={22} />
                          <span className="truncate" style={{ fontSize: '0.86rem', maxWidth: 160 }}>{commit.author}</span>
                        </div>
                      </td>
                      <td className="num hide-mobile" style={{ whiteSpace: 'nowrap' }}>
                        {commit.totalChanges > 0 ? (
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                            <span className="diff-stat add">+{commit.additions}</span>
                            <span className="diff-stat del">−{commit.deletions}</span>
                            <DiffBlocks add={commit.additions} del={commit.deletions} />
                          </span>
                        ) : <span className="muted">—</span>}
                      </td>
                      <td className="num" style={{ whiteSpace: 'nowrap', fontSize: '0.84rem' }} title={absoluteDate(commit.date)}>
                        {relativeTime(commit.date)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {(hasPrev || hasNext) && !search && !isError && (
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px', borderTop: '1px solid var(--border-subtle)' }}>
              <button className="btn btn-sm" disabled={!hasPrev} onClick={() => goTo(page - 1)}>
                <ChevronLeft size={14} /> Newer
              </button>
              <button className="btn btn-sm" disabled={!hasNext} onClick={() => goTo(page + 1)}>
                Older <ChevronRight size={14} />
              </button>
            </div>
          )}
        </motion.div>
      )}
    </motion.div>
  );
}
