import { useEffect, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { Activity, ArrowUpRight, ExternalLink, KeyRound, Unplug } from 'lucide-react';
import { fetchTraceList, formatMs, LANGFUSE_URL, type TraceListRow } from '../api/traces';
import TraceViewer from '../components/TraceViewer';
import { Alert, EmptyState, LoadingState, PageHeader } from '../components/ui';
import { containerVariants, itemVariants } from '../utils/motion';
import { errorMessage, relativeTime, shortRepo } from '../utils/format';
import { isRunning, stateMeta } from '../utils/jobState';

/**
 * Every job's LLM calls, shown here rather than in Langfuse's own UI.
 *
 * This page used to be a link out: Langfuse can't be framed, and its API needs
 * the project's secret key, so it can't be called from the browser either. The
 * orchestrator now reads traces on the dashboard's behalf (TraceController),
 * scoped to the caller's tenant, and this page draws them.
 */
export default function LangfuseEmbed() {
  const [params, setParams] = useSearchParams();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['trace-list'],
    queryFn: fetchTraceList,
    refetchInterval: 10_000,
  });

  const jobs = useMemo(() => data?.jobs ?? [], [data]);
  const selected = params.get('job') ?? undefined;
  const selectedJob = jobs.find(j => j.jobId === selected);

  // Open the most recent job that has a trace, so the page never starts empty.
  useEffect(() => {
    if (!selected && jobs.length > 0) {
      const first = jobs.find(j => j.trace) ?? jobs[0];
      setParams({ job: first.jobId }, { replace: true });
    }
  }, [selected, jobs, setParams]);

  return (
    <motion.div variants={containerVariants} initial="hidden" animate="show">
      <PageHeader
        title="LLM traces"
        description="Every prompt, response, token and millisecond the agents spent on a job — one trace per job."
        actions={
          <a href={LANGFUSE_URL} target="_blank" rel="noreferrer" className="btn btn-secondary btn-sm">
            Langfuse <ExternalLink size={13} />
          </a>
        }
      />

      {data?.status === 'NOT_CONFIGURED' && (
        <motion.div variants={itemVariants} style={{ marginBottom: 16 }}>
          <Alert tone="warning" icon={<KeyRound size={16} />} title="LLM tracing is off">{data.message}</Alert>
        </motion.div>
      )}
      {data?.status === 'UNAVAILABLE' && (
        <motion.div variants={itemVariants} style={{ marginBottom: 16 }}>
          <Alert tone="danger" icon={<Unplug size={16} />} title="Langfuse isn't reachable">
            {data.message} Trace summaries below are missing until it is back.
          </Alert>
        </motion.div>
      )}

      {isLoading ? (
        <LoadingState label="Loading jobs…" />
      ) : isError ? (
        <Alert tone="danger" title="Couldn't load jobs">{errorMessage(error)}</Alert>
      ) : jobs.length === 0 ? (
        <motion.div variants={itemVariants} className="card">
          <EmptyState icon={<Activity size={20} />} title="No jobs yet"
            description="Traces appear here once a job runs."
            action={<Link to="/fix" className="btn btn-primary btn-sm">Ask to fix</Link>} />
        </motion.div>
      ) : (
        <motion.div variants={itemVariants} className="trace-layout">
          <div className="card" style={{ padding: 0 }}>
            <div className="trace-jobs" role="listbox" aria-label="Jobs">
              {jobs.map(j => (
                <JobItem key={j.jobId} job={j} active={j.jobId === selected}
                         onSelect={() => setParams({ job: j.jobId })} />
              ))}
            </div>
          </div>

          <div className="card">
            {selected ? (
              <>
                <div className="card-header">
                  <div>
                    <div className="card-title">
                      {selectedJob ? shortRepo(selectedJob.repo) : 'Job'}
                      <span className="mono muted" style={{ fontWeight: 400 }}>{selected.substring(0, 8)}</span>
                    </div>
                    {selectedJob && (
                      <div className="card-subtitle">{stateMeta(selectedJob.state).label} · started {relativeTime(selectedJob.createdAt)}</div>
                    )}
                  </div>
                  <Link to={`/job/${selected}`} className="btn btn-ghost btn-sm">Job <ArrowUpRight size={13} /></Link>
                </div>
                <TraceViewer key={selected} jobId={selected} live={!!selectedJob && isRunning(selectedJob.state)} />
              </>
            ) : (
              <EmptyState icon={<Activity size={20} />} title="Pick a job" description="Its LLM calls show here." />
            )}
          </div>
        </motion.div>
      )}
    </motion.div>
  );
}

function JobItem({ job, active, onSelect }: { job: TraceListRow; active: boolean; onSelect: () => void }) {
  const meta = stateMeta(job.state);
  return (
    <button role="option" aria-selected={active} className={`trace-job ${active ? 'active' : ''}`} onClick={onSelect}>
      <div className="trace-job-top">
        <span className="truncate" style={{ fontWeight: 500 }}>{shortRepo(job.repo)}</span>
        <span className={`badge ${meta.badge}`}>{meta.label}</span>
      </div>
      <div className="trace-job-meta tabular">
        <span className="mono">{job.jobId.substring(0, 8)}</span>
        <span>{relativeTime(job.createdAt)}</span>
        {job.trace
          ? <><span>{job.trace.calls} calls</span><span>{formatMs(job.trace.latencyMs)}</span></>
          : <span>no trace</span>}
      </div>
    </button>
  );
}
