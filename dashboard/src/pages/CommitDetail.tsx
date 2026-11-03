import { useState, useRef, useEffect } from 'react';
import { useParams, useSearchParams, useNavigate, Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { useQuery, useMutation } from '@tanstack/react-query';
import {
  FileCode2, Loader2, AlertCircle, Copy, Check, ChevronDown, ChevronRight, Send, Wand2,
  ExternalLink, Sparkles, Info, AlertTriangle, ChevronsDownUp, ChevronsUpDown,
} from 'lucide-react';
import { apiClient } from '../api/client';
import { containerVariants, itemVariants } from '../utils/motion';
import { errorMessage } from '../utils/format';
import { PageHeader, Alert, EmptyState, LoadingState, Avatar } from '../components/ui';

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

function absDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function useCopy() {
  const [copied, setCopied] = useState(false);
  const copy = async (text: string) => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1600);
  };
  return { copied, copy };
}

// ─── Diff ─────────────────────────────────────────────────────────────────────

type DiffKind = 'add' | 'del' | 'hunk' | 'meta' | '';

/**
 * Classifies each patch line and gives it its real line number — the new
 * file's for added and context lines, the old file's for removed ones — by
 * following the `@@ -a,b +c,d @@` hunk headers.
 */
function numberPatch(patch: string): { line: string; kind: DiffKind; no: number | null }[] {
  let oldNo = 0;
  let newNo = 0;
  return patch.split('\n').map(line => {
    if (line.startsWith('@@')) {
      const m = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)/);
      if (m) { oldNo = Number(m[1]); newNo = Number(m[2]); }
      return { line, kind: 'hunk' as const, no: null };
    }
    if (line.startsWith('---') || line.startsWith('+++')) return { line, kind: 'meta' as const, no: null };
    if (line.startsWith('+')) return { line, kind: 'add' as const, no: newNo++ };
    if (line.startsWith('-')) return { line, kind: 'del' as const, no: oldNo++ };
    if (line.startsWith('\\')) return { line, kind: 'meta' as const, no: null }; // "\ No newline at end of file"
    oldNo++;
    return { line, kind: '' as const, no: newNo++ };
  });
}

function DiffLine({ line, kind, no }: { line: string; kind: DiffKind; no: number | null }) {
  return (
    <div className={`diff-line ${kind}`}>
      <span className="ln">{no ?? ''}</span>
      <pre>{line || ' '}</pre>
    </div>
  );
}

const KNOWN_STATUS = ['added', 'removed', 'modified', 'renamed'];

function FileDiffCard({ file, expanded, onToggle }: { file: CommitFile; expanded: boolean; onToggle: () => void }) {
  const { copied, copy } = useCopy();
  const lines = file.patch ? numberPatch(file.patch) : [];

  return (
    <div className="file-diff">
      <button className={`file-diff-header ${expanded ? 'expanded' : ''}`} onClick={onToggle} aria-expanded={expanded}>
        {expanded ? <ChevronDown size={14} className="muted" /> : <ChevronRight size={14} className="muted" />}
        <FileCode2 size={14} className="muted" />
        <span className="file-diff-name" title={file.filename}>{file.filename}</span>
        <span className={`file-status ${KNOWN_STATUS.includes(file.status) ? file.status : 'other'}`}>{file.status}</span>
        {file.additions > 0 && <span className="diff-stat add">+{file.additions}</span>}
        {file.deletions > 0 && <span className="diff-stat del">−{file.deletions}</span>}
        {file.patch && (
          <span
            role="button"
            tabIndex={0}
            className="btn btn-ghost btn-sm btn-icon"
            style={{ width: 26, height: 26, color: copied ? 'var(--success)' : undefined }}
            onClick={(e) => { e.stopPropagation(); copy(file.patch!); }}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.stopPropagation(); copy(file.patch!); } }}
            title="Copy diff"
            aria-label={`Copy diff of ${file.filename}`}
          >
            {copied ? <Check size={13} /> : <Copy size={13} />}
          </span>
        )}
      </button>

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            key="diff"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.18, ease: 'easeInOut' }}
            style={{ overflow: 'hidden' }}
          >
            {file.patch ? (
              <div className="diff-body">
                {lines.map((l, i) => <DiffLine key={i} {...l} />)}
              </div>
            ) : (
              <div className="muted" style={{ padding: '14px 18px', fontSize: '0.85rem' }}>
                No diff to show — the file is binary, too large, or only renamed.
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ─── Chat ─────────────────────────────────────────────────────────────────────

const actionConfig: Record<string, { icon: React.ReactNode; label: string; badge: string }> = {
  FIX: { icon: <Wand2 size={12} />, label: 'Suggests a fix', badge: 'badge-success' },
  INVESTIGATE: { icon: <AlertTriangle size={12} />, label: 'Worth investigating', badge: 'badge-warning' },
  NONE: { icon: <Info size={12} />, label: 'Informational', badge: 'badge-info' },
};

function ChatBubble({ msg, repo, sha, shortMessage }: { msg: ChatMessage; repo: string; sha: string; shortMessage: string }) {
  const navigate = useNavigate();
  const isUser = msg.role === 'user';
  const action = msg.suggestedAction && actionConfig[msg.suggestedAction];

  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: 'spring', stiffness: 300, damping: 28 }}
      className={`chat-row ${isUser ? 'user' : ''}`}
    >
      <div className={`chat-bubble ${msg.isError ? 'error' : ''}`}>{msg.content}</div>
      {action && !isUser && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span className={`badge ${action.badge}`}>{action.icon} {action.label}</span>
          {msg.suggestedAction === 'FIX' && (
            <button
              className="btn btn-sm"
              onClick={() => navigate(
                `/fix?commitSha=${sha}&issueDescription=${encodeURIComponent(`Fix issue in commit ${sha.slice(0, 7)}: ${shortMessage}`)}&repo=${encodeURIComponent(repo)}`
              )}
            >
              <Wand2 size={13} /> Open as a fix job
            </button>
          )}
        </div>
      )}
    </motion.div>
  );
}

const STARTER_QUESTIONS = [
  'What does this commit do?',
  'Could this introduce a regression?',
  'What tests should cover this?',
  'Any security concerns?',
];

// ─── Page ─────────────────────────────────────────────────────────────────────

type Tab = 'diff' | 'chat';

export default function CommitDetail() {
  const { sha } = useParams<{ sha: string }>();
  const [searchParams] = useSearchParams();
  const repo = searchParams.get('repo') ?? '';
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<Tab>('diff');
  const { copied, copy } = useCopy();
  const [question, setQuestion] = useState('');
  const [chatHistory, setChatHistory] = useState<ChatMessage[]>([]);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  // Job trigger modal
  const [showJobModal, setShowJobModal] = useState(false);
  const [jobInstruction, setJobInstruction] = useState('');
  const [isSubmittingJob, setIsSubmittingJob] = useState(false);
  const [jobError, setJobError] = useState('');

  const chatBottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const { data: commit, isLoading, isError, error } = useQuery<CommitDiff>({
    queryKey: ['commit-diff', sha, repo],
    queryFn: async () => {
      const res = await apiClient.get(`/commits/${sha}/diff`, { params: { repo } });
      return res.data;
    },
    enabled: !!sha && !!repo,
    staleTime: 5 * 60 * 1000,
  });

  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [chatHistory]);

  // Escape closes the modal.
  useEffect(() => {
    if (!showJobModal) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !isSubmittingJob) setShowJobModal(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [showJobModal, isSubmittingJob]);

  const analyzeMutation = useMutation({
    mutationFn: async (q: string) => {
      const res = await apiClient.post(`/commits/${sha}/analyze`, {
        question: q,
        chatHistory: chatHistory.map(m => ({ role: m.role, content: m.content })),
      }, { params: { repo } });
      return res.data as { answer: string; suggested_action: string };
    },
    onSuccess: (data) => {
      setChatHistory(prev => [...prev, { role: 'assistant', content: data.answer, suggestedAction: data.suggested_action }]);
    },
    onError: (err) => {
      setChatHistory(prev => [...prev, {
        role: 'assistant',
        content: errorMessage(err, 'The commit analyst isn\'t available right now.'),
        isError: true,
      }]);
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

  const submitJob = async () => {
    if (!jobInstruction.trim()) return;
    setIsSubmittingJob(true);
    setJobError('');
    try {
      const { data } = await apiClient.post('/trigger', {
        repoUrl: repo.startsWith('http') ? repo : `https://github.com/${repo}`,
        issueDescription: jobInstruction.trim(),
        targetRepo: repo
      });
      navigate(`/job/${data.jobId}`);
    } catch (e) {
      setJobError(errorMessage(e, 'Couldn\'t start the job.'));
      setIsSubmittingJob(false);
    }
  };

  if (!sha || !repo) {
    return (
      <div className="card">
        <EmptyState
          icon={<AlertCircle size={20} />}
          title="Missing commit or repository"
          description="This link needs both a commit SHA and a ?repo= parameter."
          action={<Link to="/commits" className="btn">Back to commits</Link>}
        />
      </div>
    );
  }

  if (isLoading) return <LoadingState label="Loading commit…" />;

  if (isError || !commit) {
    return (
      <motion.div variants={containerVariants} initial="hidden" animate="show">
        <PageHeader backTo="/commits" backLabel="Commits" title="Commit unavailable" />
        <Alert tone="danger" title="Couldn't load this commit">{errorMessage(error, 'Unknown error')}</Alert>
      </motion.div>
    );
  }

  const [subject, ...bodyLines] = commit.message.split('\n');
  const body = bodyLines.join('\n').trim();
  const allCollapsed = commit.files.length > 0 && commit.files.every(f => collapsed.has(f.filename));
  const answers = chatHistory.filter(m => m.role === 'assistant').length;

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        backTo="/commits"
        backLabel="Commits"
        title={<span style={{ fontSize: '0.82em', lineHeight: 1.3 }}>{subject}</span>}
        actions={
          <button
            className="btn btn-primary"
            onClick={() => {
              setJobInstruction(`Fix issue from commit ${commit.shortSha}: ${commit.shortMessage}`);
              setJobError('');
              setShowJobModal(true);
            }}
          >
            <Wand2 size={15} /> Start a fix job
          </button>
        }
      />

      <motion.div variants={itemVariants} className="card" style={{ marginBottom: 16 }}>
        {body && (
          <pre style={{ fontFamily: 'var(--font)', whiteSpace: 'pre-wrap', color: 'var(--text-secondary)', fontSize: '0.9rem', lineHeight: 1.6, marginBottom: 16 }}>
            {body}
          </pre>
        )}
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '10px 22px', fontSize: '0.86rem' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            <Avatar name={commit.author} seed={commit.authorEmail} size={22} />
            <span style={{ color: 'var(--text-primary)', fontWeight: 500 }}>{commit.author}</span>
            <span className="muted">committed {absDate(commit.date)}</span>
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <button className="btn btn-sm mono" onClick={() => copy(sha)} title="Copy full SHA" style={{ color: 'var(--accent)' }}>
              {copied ? <Check size={12} color="var(--success)" /> : <Copy size={12} />} {commit.shortSha}
            </button>
            <a href={`https://github.com/${repo}/commit/${sha}`} target="_blank" rel="noreferrer"
               className="btn btn-ghost btn-sm btn-icon" title="View on GitHub" aria-label="View on GitHub">
              <ExternalLink size={13} />
            </a>
          </span>
          <span className="mono muted">{repo}</span>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 10, alignItems: 'center' }}>
            <span className="muted">{commit.filesChanged} file{commit.filesChanged !== 1 ? 's' : ''}</span>
            <span className="diff-stat add">+{commit.additions}</span>
            <span className="diff-stat del">−{commit.deletions}</span>
          </span>
        </div>
      </motion.div>

      <motion.div variants={itemVariants} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, marginBottom: 14, flexWrap: 'wrap' }}>
        <div className="segmented" role="tablist">
          <button role="tab" aria-selected={activeTab === 'diff'} className={`segmented-item ${activeTab === 'diff' ? 'active' : ''}`} onClick={() => setActiveTab('diff')}>
            <FileCode2 size={14} /> Files changed <span className="segmented-count">{commit.filesChanged}</span>
          </button>
          <button role="tab" aria-selected={activeTab === 'chat'} className={`segmented-item ${activeTab === 'chat' ? 'active' : ''}`} onClick={() => setActiveTab('chat')}>
            <Sparkles size={14} /> Ask the analyst {answers > 0 && <span className="segmented-count">{answers}</span>}
          </button>
        </div>
        {activeTab === 'diff' && commit.files.length > 1 && (
          <button className="btn btn-ghost btn-sm" onClick={() => setCollapsed(allCollapsed ? new Set() : new Set(commit.files.map(f => f.filename)))}>
            {allCollapsed ? <><ChevronsUpDown size={14} /> Expand all</> : <><ChevronsDownUp size={14} /> Collapse all</>}
          </button>
        )}
      </motion.div>

      <AnimatePresence mode="wait">
        {activeTab === 'diff' && (
          <motion.div key="diff" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.16 }}>
            {commit.files.length === 0 ? (
              <div className="card"><EmptyState icon={<FileCode2 size={20} />} title="No file changes" description="GitHub returned no diff for this commit." /></div>
            ) : (
              commit.files.map(file => (
                <FileDiffCard
                  key={file.filename}
                  file={file}
                  expanded={!collapsed.has(file.filename)}
                  onToggle={() => setCollapsed(prev => {
                    const next = new Set(prev);
                    if (next.has(file.filename)) next.delete(file.filename); else next.add(file.filename);
                    return next;
                  })}
                />
              ))
            )}
          </motion.div>
        )}

        {activeTab === 'chat' && (
          <motion.div key="chat" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.16 }}>
            <div className="card flush chat-panel">
              <div className="chat-messages">
                {chatHistory.length === 0 && (
                  <EmptyState
                    icon={<Sparkles size={20} />}
                    tone="accent"
                    title="Ask about this commit"
                    description="The analyst reads the full diff before answering. The conversation isn't saved."
                    action={STARTER_QUESTIONS.map(q => (
                      <button key={q} className="suggestion" onClick={() => { setQuestion(q); inputRef.current?.focus(); }}>{q}</button>
                    ))}
                  />
                )}

                {chatHistory.map((msg, i) => (
                  <ChatBubble key={i} msg={msg} repo={repo} sha={sha} shortMessage={commit.shortMessage} />
                ))}

                {analyzeMutation.isPending && (
                  <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="chat-row">
                    <div className="chat-bubble" style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-muted)' }}>
                      <Loader2 size={14} className="spinner" /> Reading the diff…
                    </div>
                  </motion.div>
                )}
                <div ref={chatBottomRef} />
              </div>

              <div className="chat-composer">
                <textarea
                  ref={inputRef}
                  id="commit-chat-input"
                  className="form-textarea"
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Ask a question — Enter to send, Shift+Enter for a new line"
                  rows={2}
                />
                <button
                  id="commit-chat-send"
                  className="btn btn-primary btn-icon"
                  style={{ height: 40, width: 40 }}
                  onClick={handleSend}
                  disabled={!question.trim() || analyzeMutation.isPending}
                  aria-label="Send"
                >
                  {analyzeMutation.isPending ? <Loader2 size={16} className="spinner" /> : <Send size={16} />}
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Job trigger modal */}
      <AnimatePresence>
        {showJobModal && (
          <motion.div
            className="modal-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => !isSubmittingJob && setShowJobModal(false)}
          >
            <motion.div
              className="modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="job-modal-title"
              initial={{ scale: 0.97, opacity: 0, y: 8 }}
              animate={{ scale: 1, opacity: 1, y: 0 }}
              exit={{ scale: 0.97, opacity: 0, y: 8 }}
              transition={{ type: 'spring', damping: 28, stiffness: 340 }}
              onClick={e => e.stopPropagation()}
            >
              <div className="modal-body">
                <h2 className="modal-title" id="job-modal-title">Start a fix job</h2>
                <p className="modal-desc">
                  Tell GitOracle what's wrong with <span className="mono">{commit.shortSha}</span>. It will investigate,
                  write a patch, run the tests and open a pull request on <span className="mono">{repo}</span>.
                </p>
                <div className="field" style={{ marginTop: 18 }}>
                  <label className="form-label" htmlFor="job-instruction">What should be fixed?</label>
                  <textarea
                    id="job-instruction"
                    className="form-textarea"
                    value={jobInstruction}
                    onChange={e => setJobInstruction(e.target.value)}
                    disabled={isSubmittingJob}
                    placeholder="e.g. This commit introduced a NullPointerException in PaymentService…"
                    rows={5}
                    autoFocus
                  />
                </div>
                {jobError && <Alert tone="danger">{jobError}</Alert>}
              </div>
              <div className="modal-footer">
                <button onClick={() => setShowJobModal(false)} disabled={isSubmittingJob} className="btn btn-ghost">Cancel</button>
                <button onClick={submitJob} disabled={isSubmittingJob || !jobInstruction.trim()} className="btn btn-primary">
                  {isSubmittingJob ? <Loader2 size={15} className="spinner" /> : <Wand2 size={15} />}
                  {isSubmittingJob ? 'Starting…' : 'Start job'}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
