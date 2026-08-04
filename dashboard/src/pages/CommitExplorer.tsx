import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { GitCommit, ChevronLeft, ChevronRight, Search, GitBranch, AlertCircle, Loader2, RefreshCw } from 'lucide-react';
import { useRepos } from '../context/RepoContext';
import { apiClient } from '../api/client';

// ─── Types ────────────────────────────────────────────────────────────────────

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

// ─── Helpers ─────────────────────────────────────────────────────────────────

function relativeTime(iso: string | null): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}d ago`;
  const mo = Math.floor(d / 30);
  if (mo < 12) return `${mo}mo ago`;
  return `${Math.floor(mo / 12)}y ago`;
}

function absoluteDate(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function gravatarUrl(email: string): string {
  // Use a deterministic color avatar based on the email hash via DiceBear
  const seed = encodeURIComponent(email || 'unknown');
  return `https://api.dicebear.com/7.x/initials/svg?seed=${seed}&backgroundColor=0f172a,1e1b4b,1e3a5f,14532d,3b1f2b&fontSize=40`;
}

// ─── Animation variants ───────────────────────────────────────────────────────

const containerVariants = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.04 } },
};
const rowVariants = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0, transition: { type: 'spring' as const, stiffness: 280, damping: 26 } },
  exit: { opacity: 0, x: -10, transition: { duration: 0.15 } },
};

// ─── Sub-components ───────────────────────────────────────────────────────────

function StatChip({ value, color, sign }: { value: number; color: string; sign: '+' | '-' }) {
  if (value === 0) return null;
  return (
    <span style={{
      fontFamily: 'var(--mono)', fontSize: '0.72rem', fontWeight: 500,
      color, padding: '1px 5px', borderRadius: 4,
      background: `${color}18`,
    }}>
      {sign}{value}
    </span>
  );
}

function EmptyState({ repoName }: { repoName?: string }) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', padding: '80px 20px', gap: 16,
      color: 'var(--text-muted)',
    }}>
      <GitCommit size={40} strokeWidth={1.2} />
      <div style={{ textAlign: 'center' }}>
        <div style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>
          No commits found
        </div>
        <div style={{ fontSize: '0.8rem' }}>
          {repoName ? `No commits matched your search in ${repoName}` : 'Add a repo to browse its history'}
        </div>
      </div>
    </div>
  );
}

function NoRepoState() {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', padding: '80px 20px', gap: 16,
      color: 'var(--text-muted)',
    }}>
      <GitBranch size={40} strokeWidth={1.2} />
      <div style={{ textAlign: 'center' }}>
        <div style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>
          No active repository
        </div>
        <div style={{ fontSize: '0.8rem' }}>
          Select or add a repo in <strong style={{ color: 'var(--text-primary)' }}>My Repos</strong> to explore its commits
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

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

  // Client-side filter by message or author
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
  const hasNext = (data?.count ?? 0) === PER_PAGE; // if we got a full page, there's likely more

  return (
    <div style={{ padding: '28px 32px', maxWidth: 1100, margin: '0 auto' }}>

      {/* ── Header ── */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
            <GitCommit size={20} color="var(--graph-blue)" />
            <h1 style={{ fontSize: '1.4rem', fontWeight: 700, fontFamily: 'var(--font-display)', margin: 0 }}>
              Commit Explorer
            </h1>
            {isFetching && !isLoading && (
              <Loader2 size={14} style={{ color: 'var(--text-muted)', animation: 'spin 1s linear infinite' }} />
            )}
          </div>
          {repo && (
            <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 5 }}>
              <GitBranch size={12} />
              <span style={{ color: 'var(--graph-blue)', fontFamily: 'var(--mono)' }}>{repo}</span>
              <span>— page {page}</span>
            </div>
          )}
        </div>

        <button
          onClick={() => refetch()}
          disabled={isFetching || !repo}
          style={{
            display: 'flex', alignItems: 'center', gap: 6,
            background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
            borderRadius: 'var(--radius-md)', padding: '7px 14px',
            color: 'var(--text-secondary)', cursor: 'pointer', fontSize: '0.8rem',
            transition: 'all 0.2s', opacity: (isFetching || !repo) ? 0.5 : 1,
          }}
        >
          <RefreshCw size={13} style={isFetching ? { animation: 'spin 1s linear infinite' } : {}} />
          Refresh
        </button>
      </div>

      {/* ── No Repo ── */}
      {!repo && <NoRepoState />}

      {repo && (
        <>
          {/* ── Search bar ── */}
          <div style={{
            position: 'relative', marginBottom: 16,
          }}>
            <Search size={14} style={{
              position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)',
              color: 'var(--text-muted)', pointerEvents: 'none',
            }} />
            <input
              id="commit-search"
              type="text"
              placeholder="Filter by message, author, or SHA…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              style={{
                width: '100%', background: 'var(--bg-surface)',
                border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-md)',
                padding: '10px 14px 10px 36px',
                color: 'var(--text-primary)', fontSize: '0.85rem',
                outline: 'none', transition: 'border-color 0.2s',
                fontFamily: 'var(--font)',
              }}
              onFocus={e => (e.target.style.borderColor = 'var(--graph-blue)')}
              onBlur={e => (e.target.style.borderColor = 'var(--border-subtle)')}
            />
            {search && (
              <button
                onClick={() => setSearch('')}
                style={{
                  position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', color: 'var(--text-muted)',
                  cursor: 'pointer', padding: 4, fontSize: '1rem', lineHeight: 1,
                }}
              >×</button>
            )}
          </div>

          {/* ── Loading ── */}
          {isLoading && (
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              padding: '60px 0', gap: 12, color: 'var(--text-muted)',
            }}>
              <Loader2 size={20} style={{ animation: 'spin 1s linear infinite' }} />
              Loading commits…
            </div>
          )}

          {/* ── Error ── */}
          {isError && (
            <div style={{
              background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)',
              borderRadius: 'var(--radius-md)', padding: '16px 20px',
              display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 16,
            }}>
              <AlertCircle size={18} color="#ef4444" style={{ flexShrink: 0, marginTop: 1 }} />
              <div>
                <div style={{ fontWeight: 600, color: '#ef4444', marginBottom: 4 }}>Failed to load commits</div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  {(error as any)?.response?.data?.hint ||
                   (error as any)?.response?.data?.error ||
                   (error as any)?.message ||
                   'Unknown error. Check that the GitOracle App is installed on this repo.'}
                </div>
              </div>
            </div>
          )}

          {/* ── Commit table ── */}
          {!isLoading && !isError && (
            <>
              {filtered.length === 0 ? (
                <EmptyState repoName={repo} />
              ) : (
                <motion.div
                  key={`${repo}-${page}-${search}`}
                  variants={containerVariants}
                  initial="hidden"
                  animate="show"
                  style={{
                    background: 'var(--bg-surface)',
                    border: '1px solid var(--border-subtle)',
                    borderRadius: 'var(--radius-lg)',
                    overflow: 'hidden',
                  }}
                >
                  {/* Table header */}
                  <div style={{
                    display: 'grid',
                    gridTemplateColumns: '80px 1fr 140px 80px 80px',
                    padding: '10px 20px',
                    borderBottom: '1px solid var(--border-subtle)',
                    fontSize: '0.72rem', fontWeight: 600,
                    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
                  }}>
                    <span>SHA</span>
                    <span>Message</span>
                    <span>Author</span>
                    <span style={{ textAlign: 'right' }}>Changes</span>
                    <span style={{ textAlign: 'right' }}>When</span>
                  </div>

                  <AnimatePresence initial={false}>
                    {filtered.map((commit, i) => (
                      <motion.div
                        key={commit.sha}
                        variants={rowVariants}
                        initial="hidden"
                        animate="show"
                        exit="exit"
                        onClick={() => navigate(`/commits/${commit.sha}?repo=${encodeURIComponent(repo!)}`)}
                        style={{
                          display: 'grid',
                          gridTemplateColumns: '80px 1fr 140px 80px 80px',
                          padding: '13px 20px',
                          alignItems: 'center',
                          borderBottom: i < filtered.length - 1 ? '1px solid var(--border-subtle)' : 'none',
                          cursor: 'pointer',
                          transition: 'background 0.15s',
                        }}
                        whileHover={{ backgroundColor: 'var(--bg-surface-hover)' }}
                      >
                        {/* SHA */}
                        <span style={{
                          fontFamily: 'var(--mono)', fontSize: '0.78rem',
                          color: 'var(--graph-blue)', letterSpacing: '0.02em',
                        }}>
                          {commit.shortSha}
                        </span>

                        {/* Message */}
                        <div style={{ minWidth: 0 }}>
                          <div style={{
                            fontSize: '0.85rem', fontWeight: 500,
                            color: 'var(--text-primary)',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            maxWidth: '100%',
                          }}>
                            {commit.shortMessage}
                          </div>
                          {commit.filesChanged > 0 && (
                            <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 2, display: 'flex', gap: 6 }}>
                              <span>{commit.filesChanged} file{commit.filesChanged !== 1 ? 's' : ''}</span>
                              <StatChip value={commit.additions} color="#4ade80" sign="+" />
                              <StatChip value={commit.deletions} color="#f87171" sign="-" />
                            </div>
                          )}
                        </div>

                        {/* Author */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <img
                            src={gravatarUrl(commit.authorEmail)}
                            alt={commit.author}
                            style={{
                              width: 24, height: 24, borderRadius: '50%',
                              border: '1px solid var(--border-subtle)', flexShrink: 0,
                            }}
                            onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
                          />
                          <span style={{
                            fontSize: '0.78rem', color: 'var(--text-secondary)',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }}>
                            {commit.author}
                          </span>
                        </div>

                        {/* Changes count */}
                        <div style={{ textAlign: 'right' }}>
                          {commit.totalChanges > 0 ? (
                            <span style={{
                              fontFamily: 'var(--mono)', fontSize: '0.75rem',
                              color: 'var(--text-muted)',
                            }}>
                              ±{commit.totalChanges}
                            </span>
                          ) : (
                            <span style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>—</span>
                          )}
                        </div>

                        {/* When */}
                        <div style={{ textAlign: 'right' }}>
                          <span
                            title={absoluteDate(commit.date)}
                            style={{ fontSize: '0.75rem', color: 'var(--text-muted)', cursor: 'help' }}
                          >
                            {relativeTime(commit.date)}
                          </span>
                        </div>
                      </motion.div>
                    ))}
                  </AnimatePresence>
                </motion.div>
              )}

              {/* ── Pagination ── */}
              {(hasPrev || hasNext) && !search && (
                <div style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  marginTop: 16,
                }}>
                  <button
                    disabled={!hasPrev}
                    onClick={() => { setPage(p => p - 1); window.scrollTo(0, 0); }}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 6,
                      background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-md)', padding: '8px 16px',
                      color: hasPrev ? 'var(--text-primary)' : 'var(--text-muted)',
                      cursor: hasPrev ? 'pointer' : 'default',
                      fontSize: '0.82rem', transition: 'all 0.2s',
                      opacity: hasPrev ? 1 : 0.4,
                    }}
                  >
                    <ChevronLeft size={14} /> Newer
                  </button>

                  <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                    Page {page} · {filtered.length} commits shown
                  </span>

                  <button
                    disabled={!hasNext}
                    onClick={() => { setPage(p => p + 1); window.scrollTo(0, 0); }}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 6,
                      background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-md)', padding: '8px 16px',
                      color: hasNext ? 'var(--text-primary)' : 'var(--text-muted)',
                      cursor: hasNext ? 'pointer' : 'default',
                      fontSize: '0.82rem', transition: 'all 0.2s',
                      opacity: hasNext ? 1 : 0.4,
                    }}
                  >
                    Older <ChevronRight size={14} />
                  </button>
                </div>
              )}
            </>
          )}
        </>
      )}

      {/* Spin keyframe (reused from index.css if available, else inline) */}
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
