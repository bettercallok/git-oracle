import { useState, useRef, useEffect } from 'react';
import { useParams, useSearchParams, useNavigate, Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { useQuery, useMutation } from '@tanstack/react-query';
import {
  ArrowLeft, GitCommit, User, Calendar, FileCode2,
  MessageSquare, Loader2, AlertCircle, Copy, Check,
  ChevronDown, ChevronRight, Send, Wand2, Plus, Minus,
  GitBranch, ExternalLink, Sparkles, Info, AlertTriangle,
} from 'lucide-react';
import { apiClient } from '../api/client';

// ─── Types ────────────────────────────────────────────────────────────────────

interface CommitFile {
  filename: string;
  status: 'added' | 'modified' | 'removed' | 'renamed' | string;
  additions: number;
  deletions: number;
  changes: number;
  patch: string | null;
}

interface CommitDiff {
  sha: string;
  shortSha: string;
  repo: string;
  message: string;
  shortMessage: string;
  author: string;
  authorEmail: string;
  date: string | null;
  additions: number;
  deletions: number;
  totalChanges: number;
  filesChanged: number;
  files: CommitFile[];
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  suggestedAction?: string;
  isError?: boolean;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function absDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    month: 'long', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function copyToClipboard(text: string): Promise<void> {
  return navigator.clipboard.writeText(text);
}

// ─── Diff line rendering ──────────────────────────────────────────────────────

function DiffLine({ line, index }: { line: string; index: number }) {
  const isAdd    = line.startsWith('+') && !line.startsWith('+++');
  const isDel    = line.startsWith('-') && !line.startsWith('---');
  const isHunk   = line.startsWith('@@');
  const isMeta   = line.startsWith('---') || line.startsWith('+++');

  let bg = 'transparent';
  let color = 'var(--text-secondary)';
  let borderLeft = '3px solid transparent';

  if (isAdd)  { bg = 'rgba(74,222,128,0.08)';  color = '#86efac'; borderLeft = '3px solid #4ade80'; }
  if (isDel)  { bg = 'rgba(248,113,113,0.08)'; color = '#fca5a5'; borderLeft = '3px solid #f87171'; }
  if (isHunk) { bg = 'rgba(99,102,241,0.1)';  color = '#a5b4fc'; borderLeft = '3px solid #6366f1'; }
  if (isMeta) { color = 'var(--text-muted)'; }

  return (
    <div style={{
      display: 'flex', background: bg, borderLeft,
      minHeight: 20,
    }}>
      <span style={{
        minWidth: 40, padding: '0 8px', textAlign: 'right',
        color: 'var(--text-muted)', fontSize: '0.7rem',
        lineHeight: '20px', userSelect: 'none', flexShrink: 0,
        borderRight: '1px solid var(--border-subtle)',
      }}>
        {isHunk ? '' : index + 1}
      </span>
      <pre style={{
        margin: 0, padding: '0 12px', fontFamily: 'var(--mono)',
        fontSize: '0.75rem', color, lineHeight: '20px',
        whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1,
      }}>{line || ' '}</pre>
    </div>
  );
}

// ─── File diff card ───────────────────────────────────────────────────────────

function FileDiffCard({ file }: { file: CommitFile }) {
  const [expanded, setExpanded] = useState(true);
  const [diffCopied, setDiffCopied] = useState(false);

  const handleCopyDiff = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (file.patch) {
      await copyToClipboard(file.patch);
      setDiffCopied(true);
      setTimeout(() => setDiffCopied(false), 1800);
    }
  };

  const statusColor: Record<string, string> = {
    added: '#4ade80', removed: '#f87171', modified: '#60a5fa',
    renamed: '#c084fc',
  };
  const color = statusColor[file.status] || 'var(--text-muted)';
  const lines = file.patch ? file.patch.split('\n') : [];

  return (
    <div style={{
      border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-md)',
      overflow: 'hidden', marginBottom: 12,
    }}>
      {/* File header */}
      <button
        onClick={() => setExpanded(e => !e)}
        style={{
          width: '100%', display: 'flex', alignItems: 'center', gap: 10,
          padding: '10px 16px', background: 'var(--bg-surface-raised)',
          border: 'none', cursor: 'pointer', textAlign: 'left',
          borderBottom: expanded ? '1px solid var(--border-subtle)' : 'none',
        }}
      >
        {expanded ? <ChevronDown size={14} color="var(--text-muted)" /> : <ChevronRight size={14} color="var(--text-muted)" />}
        <FileCode2 size={14} color={color} />
        <span style={{
          fontFamily: 'var(--mono)', fontSize: '0.8rem',
          color: 'var(--text-primary)', flex: 1, textAlign: 'left',
        }}>
          {file.filename}
        </span>
        <span style={{
          fontSize: '0.68rem', fontWeight: 600, padding: '2px 7px',
          borderRadius: 4, background: `${color}18`, color,
          textTransform: 'uppercase', letterSpacing: '0.05em',
        }}>
          {file.status}
        </span>
        {file.additions > 0 && (
          <span style={{ fontFamily: 'var(--mono)', fontSize: '0.72rem', color: '#4ade80' }}>+{file.additions}</span>
        )}
        {file.deletions > 0 && (
          <span style={{ fontFamily: 'var(--mono)', fontSize: '0.72rem', color: '#f87171' }}>−{file.deletions}</span>
        )}
        {file.patch && (
          <span
            role="button"
            onClick={handleCopyDiff}
            title="Copy diff"
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 4,
              padding: '3px 7px', borderRadius: 6,
              background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
              color: diffCopied ? '#4ade80' : 'var(--text-muted)',
              fontSize: '0.72rem', cursor: 'pointer', flexShrink: 0,
            }}
          >
            {diffCopied ? <Check size={11} /> : <Copy size={11} />}
          </span>
        )}
      </button>

      {/* Diff body */}
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            key="diff"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            style={{ overflow: 'hidden' }}
          >
            {file.patch ? (
              <div style={{ overflowX: 'auto', maxHeight: 500, overflowY: 'auto' }}>
                {lines.map((line, i) => (
                  <DiffLine key={i} line={line} index={i} />
                ))}
              </div>
            ) : (
              <div style={{
                padding: '16px 20px', color: 'var(--text-muted)',
                fontSize: '0.8rem', fontStyle: 'italic',
              }}>
                No diff available for this file.
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ─── AI Chat bubble ───────────────────────────────────────────────────────────

const actionConfig: Record<string, { icon: React.ReactNode; label: string; color: string }> = {
  FIX: {
    icon: <Wand2 size={12} />,
    label: 'Suggested Action: Apply Fix',
    color: '#4ade80',
  },
  INVESTIGATE: {
    icon: <AlertTriangle size={12} />,
    label: 'Suggested Action: Investigate Further',
    color: '#f59e0b',
  },
  NONE: {
    icon: <Info size={12} />,
    label: 'Informational',
    color: '#60a5fa',
  },
};

function ChatBubble({
  msg, repo, sha, shortMessage,
}: {
  msg: ChatMessage; repo: string; sha: string; shortMessage: string;
}) {
  const navigate = useNavigate();
  const isUser = msg.role === 'user';
  const action = msg.suggestedAction && actionConfig[msg.suggestedAction];

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: 'spring', stiffness: 280, damping: 26 }}
      style={{
        display: 'flex', flexDirection: 'column',
        alignItems: isUser ? 'flex-end' : 'flex-start',
        marginBottom: 14,
      }}
    >
      <div style={{
        maxWidth: '85%',
        background: isUser ? 'var(--graph-blue)' : 'var(--bg-surface-raised)',
        border: isUser ? 'none' : '1px solid var(--border-subtle)',
        borderRadius: isUser ? '14px 14px 4px 14px' : '14px 14px 14px 4px',
        padding: '10px 14px',
        color: isUser ? '#fff' : (msg.isError ? '#f87171' : 'var(--text-primary)'),
        fontSize: '0.85rem',
        lineHeight: 1.65,
        whiteSpace: 'pre-wrap',
      }}>
        {msg.content}
      </div>

      {/* Suggested action chip */}
      {action && !isUser && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, marginTop: 8,
        }}>
          <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: '4px 10px', borderRadius: 20,
            background: `${action.color}18`, border: `1px solid ${action.color}44`,
            color: action.color, fontSize: '0.72rem', fontWeight: 600,
          }}>
            {action.icon} {action.label}
          </div>
          {msg.suggestedAction === 'FIX' && (
            <button
              onClick={() => navigate(
                `/fix?commitSha=${sha}&issueDescription=${encodeURIComponent(`Fix issue in commit ${sha.slice(0, 7)}: ${shortMessage}`)}&repo=${encodeURIComponent(repo)}`
              )}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 5,
                padding: '4px 10px', borderRadius: 20,
                background: 'rgba(99,102,241,0.15)', border: '1px solid rgba(99,102,241,0.4)',
                color: '#a5b4fc', fontSize: '0.72rem', fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              <Wand2 size={11} /> Apply Fix as PR
            </button>
          )}
        </div>
      )}
    </motion.div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

type Tab = 'diff' | 'chat';

export default function CommitDetail() {
  const { sha } = useParams<{ sha: string }>();
  const [searchParams] = useSearchParams();
  const repo = searchParams.get('repo') ?? '';
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<Tab>('diff');
  const [copied, setCopied] = useState(false);
  const [question, setQuestion] = useState('');
  const [chatHistory, setChatHistory] = useState<ChatMessage[]>([]);
  
  // Job Trigger Modal State
  const [showJobModal, setShowJobModal] = useState(false);
  const [jobInstruction, setJobInstruction] = useState('');
  const [isSubmittingJob, setIsSubmittingJob] = useState(false);

  const chatBottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // ── Fetch commit diff ──────────────────────────────────────────────────────
  const { data: commit, isLoading, isError, error } = useQuery<CommitDiff>({
    queryKey: ['commit-diff', sha, repo],
    queryFn: async () => {
      const res = await apiClient.get(`/commits/${sha}/diff`, { params: { repo } });
      return res.data;
    },
    enabled: !!sha && !!repo,
    staleTime: 5 * 60 * 1000,
  });

  // ── Scroll chat to bottom on new message ──────────────────────────────────
  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatHistory]);

  // ── Analyze mutation ───────────────────────────────────────────────────────
  const analyzeMutation = useMutation({
    mutationFn: async (q: string) => {
      const res = await apiClient.post(`/commits/${sha}/analyze`, {
        question: q,
        chatHistory: chatHistory.map(m => ({ role: m.role, content: m.content })),
      }, { params: { repo } });
      return res.data as { answer: string; suggested_action: string };
    },
    onSuccess: (data, q) => {
      setChatHistory(prev => [
        ...prev,
        { role: 'assistant', content: data.answer, suggestedAction: data.suggested_action },
      ]);
    },
    onError: (err: any) => {
      setChatHistory(prev => [
        ...prev,
        {
          role: 'assistant',
          content: err?.response?.data?.detail || err?.response?.data?.error || 'The AI agent is not available right now.',
          isError: true,
        },
      ]);
    },
  });

  const handleSend = () => {
    const q = question.trim();
    if (!q || analyzeMutation.isPending) return;
    setChatHistory(prev => [...prev, { role: 'user', content: q }]);
    setQuestion('');
    analyzeMutation.mutate(q);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  // ── Copy SHA ───────────────────────────────────────────────────────────────
  const handleCopy = async () => {
    if (sha) {
      await copyToClipboard(sha);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    }
  };

  // ── Render ─────────────────────────────────────────────────────────────────

  if (!sha || !repo) {
    return (
      <div style={{ padding: '40px 32px', textAlign: 'center', color: 'var(--text-muted)' }}>
        <AlertCircle size={32} style={{ marginBottom: 12 }} />
        <div>Missing commit SHA or repo parameter.</div>
        <Link to="/commits" style={{ color: 'var(--graph-blue)', marginTop: 12, display: 'inline-block' }}>
          ← Back to Commit Explorer
        </Link>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '80px 0', gap: 14, color: 'var(--text-muted)' }}>
        <Loader2 size={28} style={{ animation: 'spin 1s linear infinite', color: 'var(--graph-blue)' }} />
        <div style={{ fontSize: '0.9rem' }}>Loading commit…</div>
      </div>
    );
  }

  if (isError || !commit) {
    return (
      <div style={{ padding: '28px 32px' }}>
        <Link to="/commits" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--text-muted)', textDecoration: 'none', marginBottom: 20, fontSize: '0.85rem' }}>
          <ArrowLeft size={14} /> Back to Commit Explorer
        </Link>
        <div style={{
          background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)',
          borderRadius: 'var(--radius-md)', padding: '20px 24px',
          display: 'flex', alignItems: 'flex-start', gap: 12,
        }}>
          <AlertCircle size={18} color="#ef4444" style={{ flexShrink: 0, marginTop: 2 }} />
          <div>
            <div style={{ fontWeight: 600, color: '#ef4444', marginBottom: 6 }}>Failed to load commit</div>
            <div style={{ fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
              {(error as any)?.response?.data?.error || (error as any)?.message || 'Unknown error'}
            </div>
          </div>
        </div>
      </div>
    );
  }

  const tabStyle = (tab: Tab) => ({
    padding: '8px 18px', fontSize: '0.85rem', fontWeight: 500,
    border: 'none', cursor: 'pointer', borderRadius: 'var(--radius-md)',
    transition: 'all 0.15s',
    background: activeTab === tab ? 'var(--graph-blue)' : 'transparent',
    color: activeTab === tab ? '#fff' : 'var(--text-muted)',
  });

  const STARTER_QUESTIONS = [
    'What does this commit do?',
    'Could this introduce a regression?',
    'What tests should I write for this?',
    'Are there any security concerns?',
  ];

  return (
    <div style={{ padding: '28px 32px', maxWidth: 1100, margin: '0 auto' }}>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>

      {/* ── Back link ── */}
      <Link
        to="/commits"
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          color: 'var(--text-muted)', textDecoration: 'none',
          fontSize: '0.82rem', marginBottom: 20,
          transition: 'color 0.15s',
        }}
        onMouseEnter={e => (e.currentTarget.style.color = 'var(--text-primary)')}
        onMouseLeave={e => (e.currentTarget.style.color = 'var(--text-muted)')}
      >
        <ArrowLeft size={14} /> Commit Explorer
      </Link>

      {/* ── Commit header card ── */}
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        style={{
          background: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: 'var(--radius-lg)',
          padding: '20px 24px',
          marginBottom: 20,
        }}
      >
        {/* SHA + message row */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 14 }}>
          <GitCommit size={18} color="var(--graph-blue)" style={{ marginTop: 2, flexShrink: 0 }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <h1 style={{
              fontSize: '1.1rem', fontWeight: 700, color: 'var(--text-primary)',
              margin: 0, marginBottom: 4, lineHeight: 1.4,
            }}>
              {commit.message.split('\n')[0]}
            </h1>
            {commit.message.includes('\n') && (
              <pre style={{
                margin: '6px 0 0', fontSize: '0.8rem', color: 'var(--text-muted)',
                fontFamily: 'var(--font)', whiteSpace: 'pre-wrap', lineHeight: 1.6,
              }}>
                {commit.message.split('\n').slice(1).join('\n').trim()}
              </pre>
            )}
          </div>
        </div>

        {/* Meta row */}
        <div style={{
          display: 'flex', flexWrap: 'wrap', gap: '10px 24px',
          paddingTop: 14, borderTop: '1px solid var(--border-subtle)',
        }}>
          {/* SHA */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>SHA</span>
            <button
              onClick={handleCopy}
              title="Copy full SHA"
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 5,
                background: 'var(--bg-surface-raised)', border: '1px solid var(--border-subtle)',
                borderRadius: 6, padding: '3px 8px', cursor: 'pointer',
                fontFamily: 'var(--mono)', fontSize: '0.78rem', color: 'var(--graph-blue)',
                transition: 'all 0.15s',
              }}
            >
              {copied ? <Check size={12} color="#4ade80" /> : <Copy size={12} />}
              {commit.shortSha}
            </button>
            <a
              href={`https://github.com/${repo}/commit/${sha}`}
              target="_blank" rel="noreferrer"
              style={{ color: 'var(--text-muted)', display: 'flex', alignItems: 'center' }}
            >
              <ExternalLink size={13} />
            </a>
          </div>

          {/* Author */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <User size={13} color="var(--text-muted)" />
            <span style={{ fontSize: '0.82rem', color: 'var(--text-secondary)' }}>{commit.author}</span>
          </div>

          {/* Date */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Calendar size={13} color="var(--text-muted)" />
            <span style={{ fontSize: '0.82rem', color: 'var(--text-secondary)' }}>{absDate(commit.date)}</span>
          </div>

          {/* Repo */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <GitBranch size={13} color="var(--text-muted)" />
            <span style={{ fontFamily: 'var(--mono)', fontSize: '0.78rem', color: 'var(--text-muted)' }}>{repo}</span>
          </div>

          {/* Stats */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto' }}>
            <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>{commit.filesChanged} file{commit.filesChanged !== 1 ? 's' : ''}</span>
            {commit.additions > 0 && (
              <span style={{ fontFamily: 'var(--mono)', fontSize: '0.78rem', color: '#4ade80', display: 'flex', alignItems: 'center', gap: 2 }}>
                <Plus size={11} />{commit.additions}
              </span>
            )}
            {commit.deletions > 0 && (
              <span style={{ fontFamily: 'var(--mono)', fontSize: '0.78rem', color: '#f87171', display: 'flex', alignItems: 'center', gap: 2 }}>
                <Minus size={11} />{commit.deletions}
              </span>
            )}
          </div>
        </div>
      </motion.div>

      {/* ── Apply Fix as PR shortcut ── */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <button
          onClick={() => {
            setJobInstruction(`Fix issue from commit ${commit.shortSha}: ${commit.shortMessage}`);
            setShowJobModal(true);
          }}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 7,
            padding: '8px 16px', borderRadius: 'var(--radius-md)',
            background: 'var(--graph-blue)', border: 'none',
            color: '#fff', fontWeight: 600, fontSize: '0.85rem', cursor: 'pointer',
            transition: 'all 0.2s', boxShadow: '0 2px 8px rgba(99,102,241,0.2)'
          }}
          onMouseEnter={e => {
            (e.currentTarget as HTMLButtonElement).style.transform = 'translateY(-1px)';
            (e.currentTarget as HTMLButtonElement).style.boxShadow = '0 4px 12px rgba(99,102,241,0.3)';
          }}
          onMouseLeave={e => {
            (e.currentTarget as HTMLButtonElement).style.transform = 'translateY(0)';
            (e.currentTarget as HTMLButtonElement).style.boxShadow = '0 2px 8px rgba(99,102,241,0.2)';
          }}
        >
          <Wand2 size={14} /> Trigger Manual Job
        </button>
      </div>

      {/* ── Tab switcher ── */}
      <div style={{
        display: 'flex', gap: 4, marginBottom: 16,
        background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
        borderRadius: 'var(--radius-md)', padding: 4, width: 'fit-content',
      }}>
        <button style={tabStyle('diff')} onClick={() => setActiveTab('diff')}>
          <FileCode2 size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
          Files Changed ({commit.filesChanged})
        </button>
        <button style={tabStyle('chat')} onClick={() => setActiveTab('chat')}>
          <Sparkles size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
          AI Analysis
          {chatHistory.length > 0 && (
            <span style={{
              marginLeft: 6, background: 'rgba(255,255,255,0.25)',
              borderRadius: 10, padding: '1px 6px', fontSize: '0.7rem',
            }}>
              {chatHistory.filter(m => m.role === 'assistant').length}
            </span>
          )}
        </button>
      </div>

      {/* ── DIFF TAB ── */}
      <AnimatePresence mode="wait">
        {activeTab === 'diff' && (
          <motion.div
            key="diff"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.2 }}
          >
            {commit.files.length === 0 ? (
              <div style={{
                textAlign: 'center', padding: '60px 0',
                color: 'var(--text-muted)', fontSize: '0.9rem',
              }}>
                No file diffs available for this commit.
              </div>
            ) : (
              commit.files.map(file => (
                <FileDiffCard key={file.filename} file={file} />
              ))
            )}
          </motion.div>
        )}

        {/* ── CHAT TAB ── */}
        {activeTab === 'chat' && (
          <motion.div
            key="chat"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.2 }}
          >
            <div style={{
              background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-lg)', overflow: 'hidden',
              display: 'flex', flexDirection: 'column', minHeight: 500,
            }}>
              {/* Chat header */}
              <div style={{
                padding: '14px 20px', borderBottom: '1px solid var(--border-subtle)',
                display: 'flex', alignItems: 'center', gap: 8,
              }}>
                <Sparkles size={15} color="var(--graph-blue)" />
                <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Commit Analyst
                </span>
                <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', marginLeft: 4 }}>
                  Ask anything about this commit's changes
                </span>
              </div>

              {/* Messages */}
              <div style={{ flex: 1, overflowY: 'auto', padding: '20px 20px 8px' }}>
                {chatHistory.length === 0 && (
                  <motion.div
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    style={{ textAlign: 'center', paddingBottom: 20 }}
                  >
                    <div style={{
                      width: 48, height: 48, borderRadius: '50%',
                      background: 'rgba(94,106,210,0.12)', border: '1px solid rgba(94,106,210,0.3)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      margin: '0 auto 16px',
                    }}>
                      <MessageSquare size={20} color="var(--graph-blue)" />
                    </div>
                    <div style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>
                      Ask the AI about this commit
                    </div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 20 }}>
                      It has full context of the diff. Chat is ephemeral.
                    </div>
                    {/* Quick-start prompts */}
                    <div style={{
                      display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center',
                    }}>
                      {STARTER_QUESTIONS.map(q => (
                        <button
                          key={q}
                          onClick={() => {
                            setQuestion(q);
                            inputRef.current?.focus();
                          }}
                          style={{
                            padding: '6px 12px', borderRadius: 20,
                            background: 'var(--bg-surface-raised)',
                            border: '1px solid var(--border-subtle)',
                            color: 'var(--text-secondary)', fontSize: '0.78rem',
                            cursor: 'pointer', transition: 'all 0.15s',
                          }}
                          onMouseEnter={e => {
                            (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--graph-blue)';
                            (e.currentTarget as HTMLButtonElement).style.color = 'var(--text-primary)';
                          }}
                          onMouseLeave={e => {
                            (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--border-subtle)';
                            (e.currentTarget as HTMLButtonElement).style.color = 'var(--text-secondary)';
                          }}
                        >
                          {q}
                        </button>
                      ))}
                    </div>
                  </motion.div>
                )}

                {chatHistory.map((msg, i) => (
                  <ChatBubble
                    key={i} msg={msg}
                    repo={repo} sha={sha!} shortMessage={commit.shortMessage}
                  />
                ))}

                {analyzeMutation.isPending && (
                  <motion.div
                    initial={{ opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}
                  >
                    <div style={{
                      background: 'var(--bg-surface-raised)', border: '1px solid var(--border-subtle)',
                      borderRadius: '14px 14px 14px 4px', padding: '10px 14px',
                      display: 'flex', alignItems: 'center', gap: 8,
                    }}>
                      <Loader2 size={14} style={{ animation: 'spin 1s linear infinite', color: 'var(--graph-blue)' }} />
                      <span style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>Analysing…</span>
                    </div>
                  </motion.div>
                )}

                <div ref={chatBottomRef} />
              </div>

              {/* Input bar */}
              <div style={{
                padding: '12px 16px', borderTop: '1px solid var(--border-subtle)',
                display: 'flex', gap: 10, alignItems: 'flex-end',
              }}>
                <textarea
                  ref={inputRef}
                  id="commit-chat-input"
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Ask about this commit… (Enter to send, Shift+Enter for newline)"
                  rows={2}
                  style={{
                    flex: 1, background: 'var(--bg-surface-raised)',
                    border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-md)',
                    padding: '9px 13px', color: 'var(--text-primary)', fontSize: '0.85rem',
                    fontFamily: 'var(--font)', resize: 'none', outline: 'none',
                    lineHeight: 1.5, transition: 'border-color 0.2s',
                  }}
                  onFocus={e => (e.target.style.borderColor = 'var(--graph-blue)')}
                  onBlur={e => (e.target.style.borderColor = 'var(--border-subtle)')}
                />
                <button
                  id="commit-chat-send"
                  onClick={handleSend}
                  disabled={!question.trim() || analyzeMutation.isPending}
                  style={{
                    width: 40, height: 40, borderRadius: 'var(--radius-md)',
                    background: question.trim() && !analyzeMutation.isPending
                      ? 'var(--graph-blue)' : 'var(--bg-surface-raised)',
                    border: '1px solid var(--border-subtle)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    cursor: question.trim() && !analyzeMutation.isPending ? 'pointer' : 'default',
                    transition: 'all 0.2s', flexShrink: 0,
                    color: question.trim() && !analyzeMutation.isPending ? '#fff' : 'var(--text-muted)',
                  }}
                >
                  {analyzeMutation.isPending
                    ? <Loader2 size={16} style={{ animation: 'spin 1s linear infinite' }} />
                    : <Send size={16} />
                  }
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* ── Job Trigger Modal ── */}
      <AnimatePresence>
        {showJobModal && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            style={{
              position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
              background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
              zIndex: 100, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20
            }}
            onClick={() => !isSubmittingJob && setShowJobModal(false)}
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0, y: 10 }}
              animate={{ scale: 1, opacity: 1, y: 0 }}
              exit={{ scale: 0.95, opacity: 0, y: 10 }}
              transition={{ type: 'spring', damping: 25, stiffness: 300 }}
              onClick={e => e.stopPropagation()}
              style={{
                background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-lg)', width: '100%', maxWidth: 500,
                padding: '28px 32px', boxShadow: '0 20px 40px rgba(0,0,0,0.4)',
              }}
            >
              <h2 style={{ fontSize: '1.2rem', fontWeight: 700, margin: '0 0 8px 0', color: 'var(--text-primary)' }}>
                Trigger Manual Job
              </h2>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 20 }}>
                Provide details about what you want GitOracle to fix or investigate for this commit.
              </p>

              <div style={{ marginBottom: 20 }}>
                <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 8 }}>
                  Instructions / Bug Description
                </label>
                <textarea
                  value={jobInstruction}
                  onChange={e => setJobInstruction(e.target.value)}
                  disabled={isSubmittingJob}
                  placeholder="e.g. Fix the null pointer exception introduced here..."
                  style={{
                    width: '100%', height: 120, padding: 12, borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--border-subtle)', background: 'var(--bg-base)',
                    color: 'var(--text-primary)', fontSize: '0.9rem', resize: 'vertical',
                    fontFamily: 'inherit'
                  }}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
                <button
                  onClick={() => setShowJobModal(false)}
                  disabled={isSubmittingJob}
                  className="btn btn-outline"
                >
                  Cancel
                </button>
                <button
                  onClick={async () => {
                    if (!jobInstruction.trim()) return;
                    setIsSubmittingJob(true);
                    try {
                      const { data } = await apiClient.post('/trigger', {
                        repoUrl: repo.startsWith('http') ? repo : \`https://github.com/\${repo}\`,
                        issueDescription: jobInstruction.trim(),
                        targetRepo: repo
                      });
                      navigate(\`/job/\${data.jobId}\`);
                    } catch (e) {
                      console.error('Failed to start job', e);
                      alert('Failed to trigger job.');
                      setIsSubmittingJob(false);
                    }
                  }}
                  disabled={isSubmittingJob || !jobInstruction.trim()}
                  className="btn btn-primary"
                  style={{ display: 'flex', alignItems: 'center', gap: 8 }}
                >
                  {isSubmittingJob ? <Loader2 size={16} className="spin" /> : <Wand2 size={16} />}
                  {isSubmittingJob ? 'Starting...' : 'Run Fixer Agent'}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
