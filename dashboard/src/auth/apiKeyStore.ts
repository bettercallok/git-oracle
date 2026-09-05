/**
 * In-memory holder for the dashboard's API key.
 *
 * Why not localStorage
 * --------------------
 * The key used to live in `localStorage` under `gitoracle_api_key`. Anything
 * running in the page can read that — every dependency, every transitive
 * dependency, and any injected script — so a single XSS anywhere in the
 * dashboard (or in the ~hundreds of packages behind it) exfiltrates a
 * credential that, until it is revoked, grants the holder full access to that
 * tenant's data. It also persisted indefinitely: closing the tab, or even the
 * browser, left the key sitting on disk.
 *
 * A module-scoped variable is not a security boundary either — script running
 * in this page can still reach the key while it is in use. What it removes is
 * *persistence*: the key exists only for the lifetime of the page, so an
 * attacker has to be executing at the same moment as an authenticated user
 * rather than reading a value left behind for them earlier. That is a real
 * reduction, and it is the honest description of what this buys.
 *
 * Why this is not the real fix
 * ----------------------------
 * The correct answer is that the browser never holds the credential at all: a
 * real login endpoint sets an `httpOnly; Secure; SameSite=Strict` session
 * cookie, which JavaScript cannot read by construction. That needs a user and
 * session system, which this product does not have yet (see CLAUDE.md's
 * "Missing entirely" list — there is no signup, login, user model, or session
 * anywhere; there is one API key). Building it is a project, not a patch, and
 * pretending otherwise here would be worse than saying so.
 *
 * The cost, stated plainly
 * ------------------------
 * A page reload now logs the user out, because there is nowhere durable left to
 * keep the key. That is a genuine usability regression and the direct price of
 * not leaving a credential on disk. `sessionStorage` would restore reloads but
 * is read by exactly the same XSS, so it would trade the whole benefit for
 * convenience.
 */

let apiKey: string | null = null;

/** Subscribers are notified so React state can follow a change made anywhere. */
const listeners = new Set<(key: string | null) => void>();

export function getApiKey(): string | null {
  return apiKey;
}

export function setApiKey(key: string | null): void {
  apiKey = key;
  listeners.forEach((listener) => listener(apiKey));
}

export function clearApiKey(): void {
  setApiKey(null);
}

export function subscribe(listener: (key: string | null) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Removes any key left in localStorage by a previous version of the dashboard.
 *
 * Without this, upgrading does not actually remove the exposure: the old value
 * simply stays on disk, readable by the same XSS, for every existing user —
 * the credential would be no longer *used* but still *present*, which is the
 * worst of both. Called once at startup.
 */
export function purgeLegacyPersistedKey(): void {
  try {
    localStorage.removeItem('gitoracle_api_key');
  } catch {
    // Storage can be unavailable (private mode, blocked cookies). Nothing to
    // clean up in that case, and failing here must not stop the app loading.
  }
}
