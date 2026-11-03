import { apiClient } from './client';

/**
 * LLM traces, read from Langfuse by the orchestrator (TraceController) and
 * scoped to the caller's tenant. Langfuse can't be framed and its API needs
 * the project's secret key, so the dashboard never talks to it directly.
 */

export type TraceStatus = 'OK' | 'NO_TRACE' | 'NOT_CONFIGURED' | 'UNAVAILABLE';

export interface TraceMessage {
  role: string;
  content: string | null;
  truncated: boolean;
}

export interface TraceCall {
  id: string;
  agent: string;
  model: string | null;
  startTime: string | null;
  endTime: string | null;
  /** ms after the job's first LLM call started */
  offsetMs: number | null;
  latencyMs: number | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number | null;
  level: 'DEBUG' | 'DEFAULT' | 'WARNING' | 'ERROR' | string;
  statusMessage: string | null;
  input: TraceMessage[];
  output: string | null;
  outputTruncated: boolean;
}

export interface TraceSummary {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  llmTimeMs: number;
  wallClockMs: number | null;
  totalCost: number | null;
  errors: number;
  agents: string[];
  models: string[];
}

export interface JobTraceResponse {
  jobId: string;
  status: TraceStatus;
  message?: string;
  trace?: { id: string; startTime: string | null; summary: TraceSummary; calls: TraceCall[] };
}

export interface TraceListRow {
  jobId: string;
  repo: string;
  state: string;
  createdAt: string | null;
  tokenBudgetUsed: number | null;
  trace: { calls: number; latencyMs: number | null; totalCost: number | null; timestamp: string | null } | null;
}

export interface TraceListResponse {
  status: TraceStatus;
  message?: string;
  jobs: TraceListRow[];
}

export async function fetchJobTrace(jobId: string): Promise<JobTraceResponse> {
  const { data } = await apiClient.get(`/jobs/${jobId}/trace`);
  return data;
}

export async function fetchTraceList(): Promise<TraceListResponse> {
  const { data } = await apiClient.get('/traces', { params: { limit: 50 } });
  return data;
}

/** Still reachable as a secondary link, for people who want Langfuse's own UI. */
export const LANGFUSE_URL = import.meta.env.VITE_LANGFUSE_URL || 'http://localhost:3002';

/** Pipeline order, so a job's agents read top to bottom the way it ran. */
export const AGENT_META: Record<string, { label: string; tone: string }> = {
  investigator:   { label: 'Investigator', tone: 'info' },
  planner:        { label: 'Planner', tone: 'accent' },
  fixer:          { label: 'Fixer', tone: 'warning' },
  reviewer:       { label: 'Reviewer', tone: 'success' },
  commit_analyst: { label: 'Commit analyst', tone: 'neutral' },
};

export const agentMeta = (agent: string) =>
  AGENT_META[agent] ?? { label: agent === 'unknown' ? 'Agent' : agent, tone: 'neutral' };

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)} s`;
  const m = Math.floor(s / 60);
  return `${m}m ${Math.round(s % 60)}s`;
}

export function formatCost(cost: number | null | undefined): string {
  if (cost == null) return '—';
  return cost < 0.01 ? `$${cost.toFixed(4)}` : `$${cost.toFixed(2)}`;
}
