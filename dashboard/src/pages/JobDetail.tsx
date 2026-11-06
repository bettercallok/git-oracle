import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import {
  CheckCircle2, Loader2, GitPullRequest, GitPullRequestClosed, RefreshCw, Send,
  Activity, Circle, XCircle, PauseCircle, MinusCircle, SearchX,
} from 'lucide-react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { stateMeta, isRunning, PIPELINE_STAGES, stageIndex } from '../utils/jobState';
import { containerVariants, itemVariants } from '../utils/motion';
import { duration, formatNumber, formatTime, shortRepo, shortSha } from '../utils/format';
import { PageHeader, Alert, EmptyState, LoadingState } from '../components/ui';
import TraceViewer from '../components/TraceViewer';

interface Job {
  id: string;
  repo: string;
  state: string;
  /** Why it's in this state — the escalation or failure reason. */
  stateReason?: string | null;
  stateChangedAt?: string | null;
  attempts?: number | null;
  // The entity field is tokenBudgetUsed; this page read `tokensUsed`, which the
  // API never sends, so it always showed 0.
  tokenBudgetUsed?: number | null;
  rootCommit?: string | null;
  causalScore?: number | null;
  baseCommit?: string | null;
  createdAt: string;
  prUrl?: string;
}

interface JobEvent {
  id: string;
  type: 'STATE_CHANGE' | 'AGENT_PROGRESS' | 'ERROR';
  fromState?: string | null;
  toState?: string | null;
  actor: string;
  message?: string | null;
  createdAt: string;
}

const fetchJob = async (id: string): Promise<Job> => {
  const { data } = await apiClient.get(`/jobs/${id}`);
  return data;
};

const fetchEvents = async (id: string): Promise<JobEvent[]> => {
  const { data } = await apiClient.get(`/jobs/${id}/events`);
  return data;
};


type StepStatus = 'done' | 'current' | 'pending' | 'skipped' | 'failed' | 'halted';

/**
 * Where the job is in the pipeline, worked out from its state and timeline.
 * A stage the timeline never entered but the job is already past (a direct-fix
 * job skips investigate and plan) is "skipped", not "done".
 */
function pipelineSteps(state: string, events: JobEvent[]) {
  const changes = events.filter(e => e.type === 'STATE_CHANGE' && e.toState);
  const entered = (i: number) => changes.some(e => PIPELINE_STAGES[i].states.includes(e.toState!));
  const now = new Date().toISOString();

  let current = stageIndex(state);
  const stopped = current === -1; // ESCALATED / FAILED
  if (stopped) {
    // The last stage the timeline shows the job reaching.
    current = 0;
    for (const e of changes) {
      const i = stageIndex(e.toState);
      if (i >= 0) current = i;
    }
  }
  const terminalPr = current === PIPELINE_STAGES.length - 1 && stateMeta(state).terminal;

  return PIPELINE_STAGES.map((stage, i) => {
    let status: StepStatus;
    if (i < current) status = changes.length > 0 && !entered(i) ? 'skipped' : 'done';
    else if (i === current) status = stopped ? (state === 'FAILED' ? 'failed' : 'halted') : terminalPr ? 'done' : 'current';
    else status = 'pending';

    // Time spent: first entry into this stage → first later change out of it.
    const start = changes.findIndex(e => stage.states.includes(e.toState!));
    let spent = '';
    if (start >= 0) {
      const exit = changes.slice(start + 1).find(e => !stage.states.includes(e.toState!));
      if (i < PIPELINE_STAGES.length - 1) spent = duration(changes[start].createdAt, exit?.createdAt ?? (status === 'current' ? now : null));
    }
    return { ...stage, status, spent };
  });
}

const STEP_ICON: Record<StepStatus, React.ReactNode> = {
  done: <CheckCircle2 size={14} />,
  current: <Loader2 size={14} className="spinner" />,
  pending: <Circle size={14} />,
  skipped: <MinusCircle size={14} />,
  failed: <XCircle size={14} />,
  halted: <PauseCircle size={14} />,
};

const STEP_SUB: Partial<Record<StepStatus, string>> = {
  current: 'In progress',
  skipped: 'Skipped',
  failed: 'Failed here',
  halted: 'Escalated here',
};

export default function JobDetail() {
  const { canWrite } = useAuth();
  const { jobId } = useParams<{ jobId: string }>();
  const queryClient = useQueryClient();
  const [instructions, setInstructions] = useState('');
  const [feedbackSent, setFeedbackSent] = useState(false);

  const { data: job, isLoading, isError } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => fetchJob(jobId!),
    enabled: !!jobId,
    refetchInterval: 3000,
  });

  const { data: events = [] } = useQuery({
    queryKey: ['job-events', jobId],
    queryFn: () => fetchEvents(jobId!),
    enabled: !!jobId,
    refetchInterval: 3000,
  });

  const feedbackMutation = useMutation({
    mutationFn: (instructions: string) =>
      apiClient.post(`/jobs/${jobId}/feedback`, { instructions }),
    onSuccess: () => {
      setFeedbackSent(true);
      setInstructions('');
      queryClient.invalidateQueries({ queryKey: ['job', jobId] });
      queryClient.invalidateQueries({ queryKey: ['job-events', jobId] });
      setTimeout(() => setFeedbackSent(false), 5000);
    }
  });

  if (isLoading) return <LoadingState label="Loading job…" />;

  if (isError || !job) {
    return (
      <div className="card">
        <EmptyState
          icon={<SearchX size={20} />}
          title="Job not found"
          description={<>There's no job with id <span className="mono">{jobId}</span>, or the gateway couldn't be reached.</>}
          action={<Link to="/" className="btn">Back to jobs</Link>}
        />
      </div>
    );
  }

  const status = job.state;
  const meta = stateMeta(status);
  const needsHuman = meta.group === 'attention' || meta.group === 'failed';
  const prClosed = meta.group === 'closed';
  // The timeline's state changes, to show how long each state ran.
  const stateChanges = events.filter(e => e.type === 'STATE_CHANGE');
  const steps = pipelineSteps(status, events);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        backTo="/"
        backLabel="All jobs"
        title={<>
          <span>Job <span className="mono" style={{ fontSize: '0.85em', fontWeight: 500 }}>{job.id.substring(0, 8)}</span></span>
          <span className={`badge ${meta.badge} ${isRunning(status) ? 'live' : ''}`} style={{ fontSize: '0.8rem' }}>{meta.label}</span>
        </>}
        description={<>
          <span className="mono" style={{ color: 'var(--text-primary)' }}>{shortRepo(job.repo)}</span>
          {' · '}started {formatTime(job.createdAt)}
          {job.stateChangedAt && !meta.terminal ? ` · ${meta.label.toLowerCase()} for ${duration(job.stateChangedAt, new Date().toISOString())}` : ''}
        </>}
        actions={<>
          {job.prUrl && (
            <a href={job.prUrl} target="_blank" rel="noreferrer" className="btn btn-primary">
              <GitPullRequest size={15} /> View pull request
            </a>
          )}
          <a href="#llm-trace" className="btn"
             onClick={e => { e.preventDefault(); document.getElementById('llm-trace')?.scrollIntoView({ behavior: 'smooth' }); }}>
            <Activity size={14} /> LLM trace
          </a>
        </>}
      />

      {/* Pipeline progress */}
      <motion.div variants={itemVariants} className="card" style={{ marginBottom: 16 }}>
        <div className="stepper" style={{ ['--steps' as string]: steps.length }}>
          {steps.map(s => (
            <div key={s.key} className={`step ${s.status}`}>
              <div className="step-bar" />
              <div className="step-label">{STEP_ICON[s.status]} <span className="truncate">{s.label}</span></div>
              <div className="step-sub">{STEP_SUB[s.status] ?? (s.spent || (s.status === 'pending' ? 'Waiting' : ' '))}</div>
            </div>
          ))}
        </div>
      </motion.div>

      <motion.div variants={itemVariants} className="stat-grid">
        <div className="card">
          <div className="stat-label">Root cause</div>
          <div className="stat-value sm mono" style={{ marginTop: 10 }}>
            {job.rootCommit ? shortSha(job.rootCommit) : <span className="muted">Not identified</span>}
          </div>
          <div className="stat-subtitle">
            {job.rootCommit && job.causalScore != null ? `Causal score ${job.causalScore.toFixed(2)}` : 'Set when the investigator ranks commits'}
          </div>
        </div>
        <div className="card">
          <div className="stat-label">Base commit</div>
          <div className="stat-value sm mono" style={{ marginTop: 10 }}>
            {job.baseCommit ? shortSha(job.baseCommit) : <span className="muted">—</span>}
          </div>
          <div className="stat-subtitle">HEAD when the repo was cloned</div>
        </div>
        <div className="card">
          <div className="stat-label">Fix attempts</div>
          <div className="stat-value" style={{ marginTop: 10 }}>{job.attempts ?? 0}</div>
          <div className="stat-subtitle">The fixer retries up to 3 times</div>
        </div>
        <div className="card">
          <div className="stat-label">Tokens used</div>
          <div className="stat-value" style={{ marginTop: 10 }}>{formatNumber(job.tokenBudgetUsed ?? 0)}</div>
          <div className="stat-subtitle">Across every agent on this job</div>
        </div>
      </motion.div>

      {(needsHuman && job.stateReason) || prClosed || job.prUrl ? (
        <motion.div variants={itemVariants} className="stack" style={{ marginBottom: 16 }}>
          {/* Why it stopped */}
          {needsHuman && job.stateReason && (
            <Alert
              tone={meta.group === 'failed' ? 'danger' : 'warning'}
              title={meta.group === 'failed' ? 'Why this job failed' : 'Why this job was escalated'}
              action={meta.group === 'attention' ? <Link to="/escalations" className="btn btn-sm">Review</Link> : undefined}
            >
              <span style={{ whiteSpace: 'pre-wrap' }}>{job.stateReason}</span>
            </Alert>
          )}

          {/* A human closed the PR without merging: finished, nothing broken. */}
          {prClosed && (
            <Alert tone="neutral" icon={<GitPullRequestClosed size={16} />} title="Pull request closed without merging">
              {job.stateReason || 'Someone closed the pull request.'}{' '}
              Use <strong>Regenerate fix</strong> below to try a different approach — it opens a new pull request.
            </Alert>
          )}

          {job.prUrl && (
            <Alert tone="success" icon={<GitPullRequest size={16} />} title={status === 'PR_MERGED' ? 'Pull request merged' : 'Pull request opened'}>
              <a href={job.prUrl} target="_blank" rel="noreferrer" className="link mono" style={{ wordBreak: 'break-all' }}>{job.prUrl}</a>
            </Alert>
          )}
        </motion.div>
      ) : null}

      <div className="grid-sidebar">
        <motion.div variants={itemVariants} className="card">
          <div className="card-header">
            <div>
              <div className="card-title">Timeline</div>
              <div className="card-subtitle">State changes and agent notes, oldest first</div>
            </div>
            {isRunning(status) && <span className="badge badge-accent live">Live</span>}
          </div>
          {events.length === 0 ? (
            <p className="muted" style={{ fontSize: '0.9rem' }}>
              No events recorded yet. Jobs created before the timeline existed have none.
            </p>
          ) : (
            <div className="timeline">
              {events.map((e, i) => {
                const isState = e.type === 'STATE_CHANGE';
                const isErr = e.type === 'ERROR';
                const toMeta = stateMeta(e.toState);
                const next = isState ? stateChanges[stateChanges.indexOf(e) + 1] : undefined;
                const spent = isState
                  ? duration(e.createdAt, next?.createdAt ?? (toMeta.terminal ? null : new Date().toISOString()))
                  : '';
                const dot = isErr ? 'var(--danger)' : toMeta.color;
                return (
                  <motion.div key={e.id} className={`timeline-item ${isState ? '' : 'minor'} ${isErr ? 'error' : ''}`}
                    initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: Math.min(i, 10) * 0.03 }}>
                    <div className="timeline-dot" style={isState || isErr ? { borderColor: dot, ...(isErr ? { background: dot } : {}) } : undefined} />
                    <div className="timeline-head">
                      <span className="timeline-title">
                        {isState ? toMeta.label : isErr ? 'Error' : (e.message || 'Progress')}
                      </span>
                      <span className="timeline-meta">
                        <span className="mono">{e.actor}</span>
                        {spent && <>· {spent}</>}
                      </span>
                    </div>
                    <div className="timeline-time">{formatTime(e.createdAt, true)}</div>
                    {e.message && (isState || isErr) && <div className="timeline-body">{e.message}</div>}
                  </motion.div>
                );
              })}
            </div>
          )}
        </motion.div>

        {/* Human feedback */}
        {canWrite && <motion.div variants={itemVariants} className="card" style={{ position: 'sticky', top: 24 }}>
          <div className="card-title" style={{ marginBottom: 4 }}>
            <RefreshCw size={15} /> Regenerate fix
          </div>
          <p className="card-subtitle" style={{ marginBottom: 16, lineHeight: 1.55 }}>
            Not happy with the patch? Tell GitOracle how you'd fix it and it will run another cycle and open a new pull request.
          </p>

          <AnimatePresence>
            {feedbackSent && (
              <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginBottom: 14 }}>
                <Alert tone="success">Instructions sent. A new fix is on its way.</Alert>
              </motion.div>
            )}
            {feedbackMutation.isError && (
              <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} style={{ marginBottom: 14 }}>
                <Alert tone="danger">Couldn't send instructions. Try again.</Alert>
              </motion.div>
            )}
          </AnimatePresence>

          <textarea
            id="feedback-instructions"
            className="form-textarea"
            value={instructions}
            onChange={e => setInstructions(e.target.value)}
            placeholder="e.g. Don't catch the exception — check for null before calling getId()."
            rows={5}
            onKeyDown={e => {
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && instructions.trim() && !feedbackMutation.isPending) {
                feedbackMutation.mutate(instructions);
              }
            }}
          />

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12, gap: 8 }}>
            <span className="muted hide-mobile" style={{ fontSize: '0.78rem' }}>
              <span className="kbd">⌘</span> <span className="kbd">Enter</span> to send
            </span>
            <button
              id="regenerate-fix-btn"
              className="btn btn-primary"
              disabled={!instructions.trim() || feedbackMutation.isPending}
              onClick={() => feedbackMutation.mutate(instructions)}
            >
              {feedbackMutation.isPending
                ? <><Loader2 size={14} className="spinner" /> Sending…</>
                : <><Send size={14} /> Regenerate fix</>}
            </button>
          </div>
        </motion.div>}
      </div>

      {/* Every LLM call this job made, read from its Langfuse trace (one trace per job). */}
      <motion.div variants={itemVariants} className="card" id="llm-trace" style={{ marginTop: 16, scrollMarginTop: 24 }}>
        <div className="card-header">
          <div>
            <div className="card-title"><Activity size={15} /> LLM trace</div>
            <div className="card-subtitle">Every prompt and response the agents exchanged with the model, in order. Click a call to see it.</div>
          </div>
          {isRunning(status) && <span className="badge badge-accent live">Live</span>}
        </div>
        <TraceViewer jobId={job.id} live={isRunning(status)} />
      </motion.div>
    </motion.div>
  );
}
