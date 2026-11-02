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
  badge: 'badge-success' | 'badge-warning' | 'badge-info' | 'badge-danger';
  group: StateGroup;
  /** CSS colour for timeline dots. */
  color: string;
  terminal: boolean;
}

const META: Record<string, StateMeta> = {
  QUEUED:        { label: 'Queued',        badge: 'badge-info',    group: 'queued',    color: 'var(--info)',    terminal: false },
  INVESTIGATING: { label: 'Investigating', badge: 'badge-warning', group: 'running',   color: 'var(--accent)',  terminal: false },
  PLANNING:      { label: 'Planning',      badge: 'badge-warning', group: 'running',   color: 'var(--accent)',  terminal: false },
  FIXING:        { label: 'Fixing',        badge: 'badge-warning', group: 'running',   color: 'var(--accent)',  terminal: false },
  TESTING:       { label: 'Testing',       badge: 'badge-warning', group: 'running',   color: 'var(--accent)',  terminal: false },
  REGENERATING:  { label: 'Regenerating',  badge: 'badge-warning', group: 'running',   color: 'var(--warning)', terminal: false },
  PR_OPENED:     { label: 'PR opened',     badge: 'badge-success', group: 'succeeded', color: 'var(--success)', terminal: true },
  PR_MERGED:     { label: 'PR merged',     badge: 'badge-success', group: 'succeeded', color: 'var(--success)', terminal: true },
  PR_CLOSED:     { label: 'PR closed',     badge: 'badge-info',    group: 'closed',    color: 'var(--text-muted)', terminal: true },
  ESCALATED:     { label: 'Escalated',     badge: 'badge-danger',  group: 'attention', color: 'var(--danger)',  terminal: false },
  FAILED:        { label: 'Failed',        badge: 'badge-danger',  group: 'failed',    color: 'var(--danger)',  terminal: true },
};

export function stateMeta(state: string | null | undefined): StateMeta {
  return (state && META[state]) || {
    label: state || 'Unknown', badge: 'badge-info', group: 'queued', color: 'var(--info)', terminal: false,
  };
}

export const isRunning = (state: string) => stateMeta(state).group === 'running';
export const isSucceeded = (state: string) => stateMeta(state).group === 'succeeded';
/** Needs a human: failed outright, or escalated for review. */
export const needsAttention = (state: string) => ['attention', 'failed'].includes(stateMeta(state).group);
