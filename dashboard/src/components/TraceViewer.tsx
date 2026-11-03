import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Activity, AlertTriangle, ChevronRight, Clock, Coins, Copy, Check, Cpu, ExternalLink,
  Hash, KeyRound, RefreshCw, Timer, Unplug,
} from 'lucide-react';
import {
  agentMeta, fetchJobTrace, formatCost, formatMs, LANGFUSE_URL,
  type TraceCall, type TraceMessage, type TraceSummary,
} from '../api/traces';
import { formatNumber, formatTime, errorMessage } from '../utils/format';
import { Alert, EmptyState, LoadingState } from './ui';

/**
 * A job's LLM calls, drawn from its Langfuse trace: a summary, a waterfall of
 * every call on one time axis, and each call's full prompt and response.
 *
 * `live` polls while the job is still running, so calls appear as agents make them.
 */
export default function TraceViewer({ jobId, live = false }: { jobId: string; live?: boolean }) {
  const { data, isLoading, isError, error, refetch, isFetching } = useQuery({
    queryKey: ['job-trace', jobId],
    queryFn: () => fetchJobTrace(jobId),
    enabled: !!jobId,
    refetchInterval: live ? 5000 : false,
  });
  const [open, setOpen] = useState<string | null>(null);

  if (isLoading) return <LoadingState label="Loading LLM trace…" />;
  if (isError || !data) {
    return (
      <Alert tone="danger" title="Couldn't load the trace"
             action={<button className="btn btn-secondary btn-sm" onClick={() => refetch()}><RefreshCw size={13} /> Retry</button>}>
        {errorMessage(error)}
      </Alert>
    );
  }

  if (data.status === 'NOT_CONFIGURED') {
    return (
      <Alert tone="warning" icon={<KeyRound size={16} />} title="LLM tracing is off">
        Set <code className="mono">LANGFUSE_PUBLIC_KEY</code> and <code className="mono">LANGFUSE_SECRET_KEY</code> in
        the root <code className="mono">.env</code> and restart the agents and orchestrator. Calls made from then on appear here.
      </Alert>
    );
  }
  if (data.status === 'UNAVAILABLE') {
    return (
      <Alert tone="danger" icon={<Unplug size={16} />} title="Langfuse isn't reachable"
             action={<button className="btn btn-secondary btn-sm" onClick={() => refetch()} disabled={isFetching}>
               <RefreshCw size={13} className={isFetching ? 'spinner' : undefined} /> Retry</button>}>
        {data.message} Start it with <code className="mono">make infra-up</code>.
      </Alert>
    );
  }
  if (data.status === 'NO_TRACE' || !data.trace || data.trace.calls.length === 0) {
    return (
      <EmptyState icon={<Activity size={20} />} title="No LLM calls yet"
        description={live
          ? 'Calls appear here as the agents make them.'
          : 'This job finished without any traced LLM calls — it may predate tracing, or failed before an agent ran.'} />
    );
  }

  const { summary, calls } = data.trace;
  return (
    <div className="trace">
      <SummaryRow summary={summary} />
      <Waterfall calls={calls} total={summary.wallClockMs} open={open} onToggle={id => setOpen(open === id ? null : id)} />
      <div className="trace-foot">
        <span className="muted">{summary.models.join(' · ')}</span>
        <a className="link" href={`${LANGFUSE_URL}/trace/${jobId}`} target="_blank" rel="noreferrer">
          Open in Langfuse <ExternalLink size={12} />
        </a>
      </div>
    </div>
  );
}

function SummaryRow({ summary }: { summary: TraceSummary }) {
  const items = [
    { icon: <Hash size={14} />, label: 'LLM calls', value: formatNumber(summary.calls) },
    { icon: <Cpu size={14} />, label: 'Tokens', value: formatNumber(summary.totalTokens),
      sub: `${formatNumber(summary.inputTokens)} in · ${formatNumber(summary.outputTokens)} out` },
    { icon: <Timer size={14} />, label: 'Time in LLM', value: formatMs(summary.llmTimeMs) },
    { icon: <Clock size={14} />, label: 'First → last call', value: formatMs(summary.wallClockMs) },
    ...(summary.totalCost != null ? [{ icon: <Coins size={14} />, label: 'Cost', value: formatCost(summary.totalCost) }] : []),
    ...(summary.errors > 0 ? [{ icon: <AlertTriangle size={14} />, label: 'Failed calls', value: String(summary.errors), danger: true }] : []),
  ];
  return (
    <div className="trace-summary">
      {items.map(i => (
        <div key={i.label} className={`trace-stat ${'danger' in i && i.danger ? 'danger' : ''}`}>
          <div className="trace-stat-label">{i.icon} {i.label}</div>
          <div className="trace-stat-value tabular">{i.value}</div>
          {'sub' in i && i.sub && <div className="trace-stat-sub">{i.sub}</div>}
        </div>
      ))}
    </div>
  );
}

function Waterfall({ calls, total, open, onToggle }: {
  calls: TraceCall[]; total: number | null; open: string | null; onToggle: (id: string) => void;
}) {
  // Number each agent's calls (Fixer #1, #2 …) so retries are recognisable.
  const ordinal = useMemo(() => {
    const seen: Record<string, number> = {};
    return calls.map(c => (seen[c.agent] = (seen[c.agent] ?? 0) + 1));
  }, [calls]);
  const span = Math.max(total ?? 0, ...calls.map(c => (c.offsetMs ?? 0) + (c.latencyMs ?? 0)), 1);

  return (
    <div className="trace-waterfall" role="list">
      <div className="trace-axis" aria-hidden>
        <span>0</span><span>{formatMs(Math.round(span / 2))}</span><span>{formatMs(span)}</span>
      </div>
      {calls.map((c, i) => {
        const meta = agentMeta(c.agent);
        const left = ((c.offsetMs ?? 0) / span) * 100;
        const width = Math.max(((c.latencyMs ?? 0) / span) * 100, 0.6);
        const isOpen = open === c.id;
        const failed = c.level === 'ERROR';
        return (
          <div key={c.id ?? i} role="listitem" className={`trace-row ${isOpen ? 'open' : ''} ${failed ? 'failed' : ''}`}>
            <button className="trace-row-head" onClick={() => onToggle(c.id)} aria-expanded={isOpen}>
              <ChevronRight size={14} className="trace-chevron" />
              <span className={`badge badge-${meta.tone} trace-agent`}>{meta.label} #{ordinal[i]}</span>
              <span className="trace-track">
                <span className={`trace-bar tone-${meta.tone} ${failed ? 'failed' : ''}`}
                      style={{ left: `${left}%`, width: `${width}%` }} />
              </span>
              <span className="trace-row-meta tabular">
                <span>{formatMs(c.latencyMs)}</span>
                <span className="muted">{formatNumber(c.totalTokens)} tok</span>
                {failed && <span className="badge badge-danger">failed</span>}
              </span>
            </button>
            <AnimatePresence initial={false}>
              {isOpen && (
                <motion.div className="trace-detail"
                  initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.18 }}>
                  <CallDetail call={c} />
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        );
      })}
    </div>
  );
}

function CallDetail({ call }: { call: TraceCall }) {
  const [tab, setTab] = useState<'response' | 'prompt'>('response');
  return (
    <div className="trace-detail-inner">
      <div className="trace-kv">
        <span><b>Model</b> <span className="mono">{call.model ?? '—'}</span></span>
        <span><b>Started</b> {formatTime(call.startTime, true)}</span>
        <span><b>Latency</b> {formatMs(call.latencyMs)}</span>
        <span><b>Tokens</b> {formatNumber(call.inputTokens)} in → {formatNumber(call.outputTokens)} out</span>
        {call.cost != null && <span><b>Cost</b> {formatCost(call.cost)}</span>}
      </div>
      {call.level === 'ERROR' && call.statusMessage && (
        <Alert tone="danger" title="This call failed">{call.statusMessage}</Alert>
      )}
      <div className="segmented" role="tablist">
        <button role="tab" aria-selected={tab === 'response'} className={`segmented-item ${tab === 'response' ? 'active' : ''}`}
                onClick={() => setTab('response')}>Response</button>
        <button role="tab" aria-selected={tab === 'prompt'} className={`segmented-item ${tab === 'prompt' ? 'active' : ''}`}
                onClick={() => setTab('prompt')}>Prompt <span className="segmented-count">{call.input.length}</span></button>
      </div>
      {tab === 'response'
        ? <TextBlock label="Response" text={prettyJson(call.output)} truncated={call.outputTruncated} />
        : call.input.map((m, i) => <MessageBlock key={i} message={m} />)}
    </div>
  );
}

function MessageBlock({ message }: { message: TraceMessage }) {
  // System prompts are long and mostly the same every call; fold them by default.
  const [expanded, setExpanded] = useState(message.role !== 'system');
  return (
    <div className="trace-message">
      <button className="trace-message-role" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>
        <ChevronRight size={12} className="trace-chevron" style={expanded ? { transform: 'rotate(90deg)' } : undefined} />
        {message.role}
        <span className="muted">{formatNumber(message.content?.length ?? 0)} chars</span>
      </button>
      {expanded && <TextBlock text={message.content ?? ''} truncated={message.truncated} />}
    </div>
  );
}

function TextBlock({ text, truncated, label }: { text: string | null; truncated: boolean; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text ?? '');
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* clipboard blocked — nothing useful to do */ }
  };
  return (
    <div className="trace-text">
      <button className="btn btn-ghost btn-sm trace-copy" onClick={copy} aria-label={`Copy ${label ?? 'text'}`}>
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </button>
      <pre className="code-block">{text || '(empty)'}</pre>
      {truncated && <div className="trace-truncated">Cut at 60,000 characters — open in Langfuse for the full text.</div>}
    </div>
  );
}

function prettyJson(text: string | null): string | null {
  if (!text) return text;
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
