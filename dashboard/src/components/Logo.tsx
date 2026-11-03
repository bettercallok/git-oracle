/**
 * GitOracle mark: an eye drawn around a commit on a line of history —
 * the oracle reading the commit graph.
 */
export function LogoMark({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path
        d="M5.5 16c2.8-4.6 6.3-6.9 10.5-6.9s7.7 2.3 10.5 6.9c-2.8 4.6-6.3 6.9-10.5 6.9S8.3 20.6 5.5 16Z"
        stroke="var(--accent-fg)" strokeWidth="1.8" strokeLinejoin="round"
      />
      <path d="M2 16h7.5M22.5 16H30" stroke="var(--accent-fg)" strokeWidth="1.8" strokeLinecap="round" opacity="0.55" />
      <circle cx="16" cy="16" r="3.4" fill="var(--accent-fg)" />
    </svg>
  );
}

export function Logo({ size = 24 }: { size?: number }) {
  return (
    <>
      <LogoMark size={size} />
      <span>Git<span className="logo-word-dim">Oracle</span></span>
    </>
  );
}
