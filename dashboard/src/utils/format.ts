/** Formatting helpers shared by every page. */

/** A backend timestamp as local date + time; '—' for null, invalid or epoch values. */
export function formatTime(raw: string | null | undefined, withSeconds = false): string {
  if (!raw) return '—';
  const d = new Date(raw);
  // Epoch or near-epoch means the DB had no timestamp yet.
  if (isNaN(d.getTime()) || d.getFullYear() < 2000) return '—';
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
  });
}

/** "3m ago", "2d ago" … */
export function relativeTime(raw: string | null | undefined): string {
  if (!raw) return '—';
  const t = new Date(raw).getTime();
  if (isNaN(t) || new Date(raw).getFullYear() < 2000) return '—';
  const m = Math.floor((Date.now() - t) / 60000);
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

/** "2m 14s" between two timestamps; empty when either is missing. */
export function duration(from: string, to?: string | null): string {
  if (!to) return '';
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (!(ms >= 0)) return '';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** "owner/repo" from a GitHub URL (or the input unchanged). */
export const shortRepo = (repo: string | null | undefined) =>
  (repo || '').replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '');

export const shortSha = (sha?: string | null) => (sha ? sha.substring(0, 8) : '');

export const formatNumber = (n: number | null | undefined) =>
  n == null ? '—' : n.toLocaleString();

/** The axios error's most useful message. */
export function errorMessage(err: unknown, fallback = 'Something went wrong.'): string {
  const e = err as { response?: { data?: { error?: string; detail?: string; hint?: string } }; message?: string };
  return e?.response?.data?.hint || e?.response?.data?.error || e?.response?.data?.detail || e?.message || fallback;
}
