import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  KeyRound, ArrowRight, Eye, EyeOff, Search, ListChecks, Wrench, FlaskConical,
  GitPullRequest, AlertCircle, Clock, Terminal, LogIn, BookOpen,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { Logo } from '../components/Logo';
import { API_BASE_URL, apiClient } from '../api/client';

const STAGES = [
  { icon: <Search size={14} />, name: 'Investigate', desc: 'Ranks the commits most likely to have caused the bug.' },
  { icon: <ListChecks size={14} />, name: 'Plan', desc: 'Scopes the fix to specific files, functions and a line budget.' },
  { icon: <Wrench size={14} />, name: 'Fix', desc: 'Writes the patch and checks it against guardrails.' },
  { icon: <FlaskConical size={14} />, name: 'Test', desc: "Runs the repository's own test suite in a sandbox." },
  { icon: <GitPullRequest size={14} />, name: 'Open a pull request', desc: 'Ready for a human to review, merge or send back.' },
];

const SIGN_IN_ERRORS: Record<string, string> = {
  not_invited: 'This GitHub account has not been invited. Ask the person who runs this GitOracle to add you.',
  cancelled: 'Sign-in was cancelled on GitHub.',
  login_failed: 'Sign-in with GitHub failed. Please try again.',
  not_configured: 'Sign in with GitHub is not set up on this server.',
};

export default function Login() {
  const [searchParams] = useSearchParams();
  const signInError = SIGN_IN_ERRORS[searchParams.get('error') ?? ''];
  // null until the server answers. An error means the same as "not available".
  const [githubLogin, setGithubLogin] = useState<boolean | null>(null);
  const [key, setKey] = useState('');
  const [reveal, setReveal] = useState(false);
  const [error, setError] = useState('');
  const { login } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    apiClient.get('/auth/config')
      .then(({ data }) => setGithubLogin(!!data.githubLogin))
      .catch(() => setGithubLogin(false));
  }, []);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!key.trim()) {
      setError('Enter an API key to continue.');
      return;
    }
    // The key isn't validated here: the gateway answers the first request
    // with 401 if it's wrong, and the API client signs the tab out again.
    login(key.trim());
    navigate('/');
  };

  return (
    <div className="auth-shell">
      <aside className="auth-aside">
        <div className="sidebar-logo" style={{ padding: 0 }}><Logo size={28} /></div>

        <motion.ol
          className="pipeline-list"
          initial="hidden"
          animate="show"
          variants={{ hidden: {}, show: { transition: { staggerChildren: 0.08, delayChildren: 0.15 } } }}
        >
          {STAGES.map(s => (
            <motion.li
              key={s.name}
              variants={{ hidden: { opacity: 0, x: -8 }, show: { opacity: 1, x: 0, transition: { duration: 0.35, ease: [0.22, 1, 0.36, 1] } } }}
            >
              <div className="pipeline-node">{s.icon}</div>
              <div>
                <div className="pipeline-name">{s.name}</div>
                <div className="pipeline-desc">{s.desc}</div>
              </div>
            </motion.li>
          ))}
        </motion.ol>

        <h2 className="auth-headline">From bug report to pull request. <span>Every step on the record.</span></h2>
        <p className="auth-lede">
          GitOracle traces a failure back through your git history, writes a scoped fix, proves it
          against your tests, and leaves a timeline you can audit.
        </p>
      </aside>

      <main className="auth-main">
        <Link to="/guide" className="btn btn-ghost btn-sm auth-top-link"><BookOpen size={14} /> Developer guide</Link>
        <motion.div
          className="auth-panel"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
        >
          <div className="sidebar-logo auth-mobile-brand" style={{ padding: 0 }}><Logo size={26} /></div>

          <h1 className="auth-title">Sign in</h1>
          <p className="auth-sub">
            {githubLogin ? 'Use your GitHub account. Access is by invitation.' : 'Use an API key issued by your GitOracle gateway.'}
          </p>

          {signInError && <div className="form-error" role="alert" style={{ marginTop: 18 }}><AlertCircle size={13} /> {signInError}</div>}

          {githubLogin && (
            <a href={`${API_BASE_URL}/auth/github/login`} className="btn btn-primary btn-lg btn-block" style={{ marginTop: 28 }}>
              <LogIn size={16} /> Sign in with GitHub
            </a>
          )}

          {/* Only for a copy without GitHub sign-in. People shouldn't handle long-lived keys, and scripts don't log in here. */}
          {githubLogin === false && <>
          <form onSubmit={handleSubmit} style={{ marginTop: 28 }} noValidate>
            <label className="form-label" htmlFor="api-key">API key</label>
            <div className="input-wrap">
              <KeyRound size={15} className="input-icon" />
              <input
                id="api-key"
                type={reveal ? 'text' : 'password'}
                className={`form-input mono-input ${error ? 'invalid' : ''}`}
                placeholder="gor_…"
                value={key}
                onChange={(e) => { setKey(e.target.value); setError(''); }}
                autoComplete="off"
                spellCheck={false}
                autoFocus
                style={{ height: 42, paddingRight: 44 }}
                aria-invalid={!!error}
                aria-describedby={error ? 'api-key-error' : undefined}
              />
              <button
                type="button"
                className="btn btn-ghost btn-sm btn-icon input-action"
                onClick={() => setReveal(r => !r)}
                aria-label={reveal ? 'Hide key' : 'Show key'}
                title={reveal ? 'Hide key' : 'Show key'}
                style={{ right: 6 }}
              >
                {reveal ? <EyeOff size={14} /> : <Eye size={14} />}
              </button>
            </div>
            {error && <div className="form-error" id="api-key-error"><AlertCircle size={13} /> {error}</div>}

            <button type="submit" className="btn btn-primary btn-lg btn-block" style={{ marginTop: 18 }}>
              Continue <ArrowRight size={16} />
            </button>
          </form>

          <div className="auth-note">
            <div className="auth-note-row">
              <Clock size={14} />
              <span>The key is kept in memory for this tab only — it is never written to disk, so reloading the page signs you out.</span>
            </div>
            <div className="auth-note-row">
              <Terminal size={14} />
              <span>
                Running your own copy? <code className="inline">./start_local.sh</code> created a key: it's{' '}
                <code className="inline">GITORACLE_API_KEY</code> in your <code className="inline">.env</code>.{' '}
                See the <Link to="/guide">developer guide</Link>.
              </span>
            </div>
          </div>
          </>}
        </motion.div>
      </main>
    </div>
  );
}
