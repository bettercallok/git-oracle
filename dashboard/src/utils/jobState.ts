/**
 * One description of every job state the backend actually sets
 * (see JobStateService / OrchestratorService).
 *
 * JobFeed and JobDetail each kept their own list and had drifted from the
 * backend: both checked SUCCESS, JobFeed also RUNNING — states nothing ever
 * sets — while PLANNING and FIXING, which the backend does set, were missing,
 * so those jobs showed as "info" and weren't counted as running.
 */
// 'closed': a human closed the PR without merging it — finished, not a
// failure of the pipeline, and nothing left for anyone to do.
export type StateGroup = 'queued' | 'running' | 'succeeded' | 'attention' | 'failed' | 'closed';

export interface StateMeta {
  label: string;
  badge: 'badge-success' | 'badge-warning' | 'badge-info' | 'badge-danger' | 'badge-accent' | 'badge-neutral';
  group: StateGroup;
  /** CSS colour for timeline dots and distribution bars. */
  color: string;
  terminal: boolean;
}

const META: Record<string, StateMeta> = {
  QUEUED:        { label: 'Queued',        badge: 'badge-neutral', group: 'queued',    color: 'var(--text-muted)', terminal: false },
  INVESTIGATING: { label: 'Investigating', badge: 'badge-accent',  group: 'running',   color: 'var(--accent)',     terminal: false },
  PLANNING:      { label: 'Planning',      badge: 'badge-accent',  group: 'running',   color: 'var(--accent)',     terminal: false },
  FIXING:        { label: 'Fixing',        badge: 'badge-accent',  group: 'running',   color: 'var(--accent)',     terminal: false },
  TESTING:       { label: 'Testing',       badge: 'badge-accent',  group: 'running',   color: 'var(--accent)',     terminal: false },
  REGENERATING:  { label: 'Regenerating',  badge: 'badge-accent',  group: 'running',   color: 'var(--accent)',     terminal: false },
  PR_OPENED:     { label: 'PR opened',     badge: 'badge-success', group: 'succeeded', color: 'var(--success)',    terminal: true },
  PR_MERGED:     { label: 'PR merged',     badge: 'badge-success', group: 'succeeded', color: 'var(--success)',    terminal: true },
  PR_CLOSED:     { label: 'PR closed',     badge: 'badge-neutral', group: 'closed',    color: 'var(--text-muted)', terminal: true },
  ESCALATED:     { label: 'Escalated',     badge: 'badge-warning', group: 'attention', color: 'var(--warning)',    terminal: false },
  FAILED:        { label: 'Failed',        badge: 'badge-danger',  group: 'failed',    color: 'var(--danger)',     terminal: true },
};

export function stateMeta(state: string | null | undefined): StateMeta {
  return (state && META[state]) || {
    label: state || 'Unknown', badge: 'badge-neutral', group: 'queued', color: 'var(--text-muted)', terminal: false,
  };
}

export const isRunning = (state: string) => stateMeta(state).group === 'running';
export const isSucceeded = (state: string) => stateMeta(state).group === 'succeeded';
/** Needs a human: failed outright, or escalated for review. */
export const needsAttention = (state: string) => ['attention', 'failed'].includes(stateMeta(state).group);

/** Display order and colour for each group, for legends and distribution bars. */
export const GROUPS: { group: StateGroup; label: string; color: string }[] = [
  { group: 'running',   label: 'Running',         color: 'var(--accent)' },
  { group: 'queued',    label: 'Queued',          color: 'var(--text-faint)' },
  { group: 'succeeded', label: 'PR opened',       color: 'var(--success)' },
  { group: 'closed',    label: 'Closed',          color: 'var(--text-muted)' },
  { group: 'attention', label: 'Escalated',       color: 'var(--warning)' },
  { group: 'failed',    label: 'Failed',          color: 'var(--danger)' },
];

/**
 * The pipeline's stages in order, and the states that mean a job is in each.
 * Used by JobDetail's stepper. REGENERATING re-enters at the fix stage.
 */
export const PIPELINE_STAGES: { key: string; label: string; states: string[] }[] = [
  { key: 'investigate', label: 'Investigate', states: ['QUEUED', 'INVESTIGATING'] },
  { key: 'plan',        label: 'Plan',        states: ['PLANNING'] },
  { key: 'fix',         label: 'Fix',         states: ['FIXING', 'REGENERATING'] },
  { key: 'test',        label: 'Test',        states: ['TESTING'] },
  { key: 'pr',          label: 'Pull request', states: ['PR_OPENED', 'PR_MERGED', 'PR_CLOSED'] },
];

/** Index of the pipeline stage a state belongs to, or -1 (ESCALATED/FAILED/unknown). */
export const stageIndex = (state: string | null | undefined) =>
  PIPELINE_STAGES.findIndex(s => !!state && s.states.includes(state));
