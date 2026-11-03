import type { ReactNode } from 'react';
import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import { AlertCircle, AlertTriangle, ArrowLeft, CheckCircle2, Info, Loader2 } from 'lucide-react';
import { itemVariants } from '../utils/motion';

/* ─── Page header ───────────────────────────────────────────────────────── */

export function PageHeader({
  title, description, actions, eyebrow, backTo, backLabel,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  /** Small label above the title. Ignored when `backTo` is set. */
  eyebrow?: ReactNode;
  backTo?: string;
  backLabel?: string;
}) {
  return (
    <motion.header variants={itemVariants} className="page-header">
      <div className="page-header-main">
        {backTo ? (
          <Link to={backTo} className="page-eyebrow"><ArrowLeft size={13} /> {backLabel ?? 'Back'}</Link>
        ) : eyebrow ? (
          <div className="page-eyebrow">{eyebrow}</div>
        ) : null}
        <h1 className="page-title">{title}</h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </motion.header>
  );
}

/* ─── Alerts ────────────────────────────────────────────────────────────── */

type Tone = 'success' | 'danger' | 'warning' | 'info' | 'accent' | 'neutral';

const TONE_ICON: Record<Tone, ReactNode> = {
  success: <CheckCircle2 size={16} />,
  danger: <AlertCircle size={16} />,
  warning: <AlertTriangle size={16} />,
  info: <Info size={16} />,
  accent: <Info size={16} />,
  neutral: <Info size={16} />,
};

export function Alert({
  tone = 'neutral', title, children, icon, action, role,
}: {
  tone?: Tone;
  title?: ReactNode;
  children?: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
  role?: 'alert' | 'status';
}) {
  return (
    <div className={`alert alert-${tone}`} role={role ?? (tone === 'danger' ? 'alert' : 'status')}>
      {icon ?? TONE_ICON[tone]}
      <div className="alert-content">
        {title && <div className="alert-title">{title}</div>}
        {children && <div className="alert-body">{children}</div>}
      </div>
      {action && <div className="alert-action">{action}</div>}
    </div>
  );
}

/* ─── Empty / loading / error states ────────────────────────────────────── */

export function EmptyState({
  icon, title, description, action, tone,
}: {
  icon: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  tone?: 'success' | 'accent';
}) {
  return (
    <div className="empty-state">
      <div className={`empty-icon ${tone ?? ''}`}>{icon}</div>
      <div className="empty-title">{title}</div>
      {description && <div className="empty-desc">{description}</div>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  );
}

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading-state" role="status">
      <Loader2 size={16} className="spinner" /> {label}
    </div>
  );
}

/* ─── Avatar ────────────────────────────────────────────────────────────── */

const AVATAR_HUES = [252, 200, 160, 28, 340, 280, 110, 12];

/**
 * Initials on a colour derived from the seed. Generated locally — the
 * previous DiceBear avatars were remote images, which the dashboard's CSP
 * (`img-src 'self' data:`) blocks, so they never rendered.
 */
export function Avatar({ name, seed, size = 24 }: { name: string; seed?: string; size?: number }) {
  const key = seed || name || '?';
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) | 0;
  const hue = AVATAR_HUES[Math.abs(hash) % AVATAR_HUES.length];
  const initials = (name || '?')
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map(p => p[0])
    .join('') || '?';
  return (
    <span
      className="avatar"
      aria-hidden="true"
      style={{
        width: size, height: size, fontSize: size * 0.4,
        background: `linear-gradient(135deg, hsl(${hue} 55% 52%), hsl(${hue + 24} 50% 40%))`,
      }}
    >
      {initials}
    </span>
  );
}
