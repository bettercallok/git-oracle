import { lazy, Suspense, useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, NavLink, Navigate, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import {
  ShieldAlert, GitMerge, Flame, BarChart3, BrainCircuit, Settings, TerminalSquare,
  Wand2, FolderGit2, GitCommitHorizontal, Sun, Moon, LogOut, Menu, X, Plus,
} from 'lucide-react';
import Login from './pages/Login';
import { RepoProvider, useRepos } from './context/RepoContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ThemeProvider, useTheme } from './context/ThemeContext';
import { Logo } from './components/Logo';
import { LoadingState } from './components/ui';
import { apiClient } from './api/client';

// Pages load on first visit instead of all up front: every page used to be in
// one 548 KB bundle. Login stays eager — the auth guard renders it immediately.
const JobFeed = lazy(() => import('./pages/JobFeed'));
const JobDetail = lazy(() => import('./pages/JobDetail'));
const EscalationQueue = lazy(() => import('./pages/EscalationQueue'));
const RiskHeatmap = lazy(() => import('./pages/RiskHeatmap'));
const EvalDashboard = lazy(() => import('./pages/EvalDashboard'));
const LangfuseEmbed = lazy(() => import('./pages/LangfuseEmbed'));
const AdminSettings = lazy(() => import('./pages/AdminSettings'));
const Simulator = lazy(() => import('./pages/Simulator'));
const FixCommand = lazy(() => import('./pages/FixCommand'));
const RepoManager = lazy(() => import('./pages/RepoManager'));
const CommitExplorer = lazy(() => import('./pages/CommitExplorer'));
const CommitDetail = lazy(() => import('./pages/CommitDetail'));

const PAGE_TITLES: [RegExp, string][] = [
  [/^\/$/, 'Jobs'],
  [/^\/job\//, 'Job'],
  [/^\/fix/, 'Ask to fix'],
  [/^\/repos/, 'Repositories'],
  [/^\/commits\/.+/, 'Commit'],
  [/^\/commits/, 'Commits'],
  [/^\/escalations/, 'Escalations'],
  [/^\/risk/, 'Risk heatmap'],
  [/^\/eval/, 'Evaluations'],
  [/^\/langfuse/, 'LLM traces'],
  [/^\/simulator/, 'Webhook simulator'],
  [/^\/settings/, 'Settings'],
];

function AuthGuard({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  // Wait for /auth/me, so a signed-in reload doesn't flash the login page.
  if (isLoading) {
    return <LoadingState />;
  }

  // Including on /login itself, where the app shell's navigation would otherwise
  // appear around the login page for someone who isn't signed in.
  if (!isAuthenticated) {
    return <Login />;
  }

  if (isAuthenticated && location.pathname === '/login') {
    // A client-side redirect. This was `window.location.href = '/'`: a full
    // page load, which drops the API key (it is held in memory only), so a
    // sign-in that rendered here before its own navigate('/') landed was
    // signed straight back out.
    return <Navigate to="/" replace />;
  }

  return <>{children}</>;
}

function PageWrapper({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -4 }}
      transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
    >
      <Suspense fallback={<LoadingState />}>
        {children}
      </Suspense>
    </motion.div>
  );
}

function AnimatedRoutes() {
  const location = useLocation();

  useEffect(() => {
    const match = PAGE_TITLES.find(([re]) => re.test(location.pathname));
    document.title = match ? `${match[1]} · GitOracle` : 'GitOracle';
  }, [location.pathname]);

  return (
    <AnimatePresence mode="wait">
      <Routes location={location} key={location.pathname}>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<PageWrapper><JobFeed /></PageWrapper>} />
        <Route path="/job/:jobId" element={<PageWrapper><JobDetail /></PageWrapper>} />
        <Route path="/fix" element={<PageWrapper><FixCommand /></PageWrapper>} />
        <Route path="/repos" element={<PageWrapper><RepoManager /></PageWrapper>} />
        <Route path="/commits" element={<PageWrapper><CommitExplorer /></PageWrapper>} />
        <Route path="/commits/:sha" element={<PageWrapper><CommitDetail /></PageWrapper>} />
        <Route path="/escalations" element={<PageWrapper><EscalationQueue /></PageWrapper>} />
        <Route path="/risk" element={<PageWrapper><RiskHeatmap /></PageWrapper>} />
        <Route path="/eval" element={<PageWrapper><EvalDashboard /></PageWrapper>} />
        <Route path="/langfuse" element={<PageWrapper><LangfuseEmbed /></PageWrapper>} />
        <Route path="/simulator" element={<PageWrapper><Simulator /></PageWrapper>} />
        <Route path="/settings" element={<PageWrapper><AdminSettings /></PageWrapper>} />
      </Routes>
    </AnimatePresence>
  );
}

function ActiveRepoPill() {
  const { activeRepo } = useRepos();
  if (!activeRepo) {
    return (
      <NavLink to="/repos" className="repo-pill empty">
        <div className="repo-pill-name" style={{ color: 'var(--text-secondary)', fontWeight: 450 }}>
          <Plus size={14} /> Connect a repository
        </div>
      </NavLink>
    );
  }
  return (
    <NavLink to="/repos" className="repo-pill" title="Change the active repository">
      <div className="repo-pill-label">Active repository</div>
      <div className="repo-pill-name">
        <span className="status-dot live" />
        <span className="truncate">{activeRepo.fullName}</span>
      </div>
    </NavLink>
  );
}

/** Live count for the Escalations nav item — same query the page itself uses. */
function useEscalationCount(enabled: boolean) {
  const { data } = useQuery({
    queryKey: ['escalations'],
    queryFn: async () => (await apiClient.get('/escalations')).data as unknown[],
    // Was 15s against the jobs page's 2s, so the badge visibly lagged the
    // page's own escalation count after anything changed.
    refetchInterval: 5000,
    enabled,
  });
  return Array.isArray(data) ? data.length : 0;
}

const linkClass = ({ isActive }: { isActive: boolean }) => `nav-link ${isActive ? 'active' : ''}`;

function Sidebar({ open }: { open: boolean }) {
  const { theme, toggleTheme } = useTheme();
  const { logout, isAuthenticated, user } = useAuth();
  const escalations = useEscalationCount(isAuthenticated);

  return (
    <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Main navigation">
      <NavLink to="/" className="sidebar-logo hide-mobile"><Logo size={26} /></NavLink>

      <ActiveRepoPill />

      <div className="sidebar-section">
        <div className="sidebar-section-label">Pipeline</div>
        <nav className="sidebar-nav">
          <NavLink to="/" end className={linkClass}><GitMerge size={16} /> Jobs</NavLink>
          <NavLink to="/fix" className={linkClass}><Wand2 size={16} /> Ask to fix</NavLink>
          <NavLink to="/escalations" className={linkClass}>
            <ShieldAlert size={16} /> Escalations
            {escalations > 0 && <span className="nav-hint" style={{ color: 'var(--warning)', background: 'var(--warning-bg)' }}>{escalations}</span>}
          </NavLink>
        </nav>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-label">Code</div>
        <nav className="sidebar-nav">
          <NavLink to="/repos" className={linkClass}><FolderGit2 size={16} /> Repositories</NavLink>
          <NavLink to="/commits" className={linkClass}><GitCommitHorizontal size={16} /> Commits</NavLink>
          <NavLink to="/risk" className={linkClass}><Flame size={16} /> Risk heatmap</NavLink>
        </nav>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-label">Quality</div>
        <nav className="sidebar-nav">
          <NavLink to="/eval" className={linkClass}><BarChart3 size={16} /> Evaluations</NavLink>
          <NavLink to="/langfuse" className={linkClass}><BrainCircuit size={16} /> LLM traces</NavLink>
        </nav>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-label">System</div>
        <nav className="sidebar-nav">
          <NavLink to="/simulator" className={linkClass}><TerminalSquare size={16} /> Webhook simulator</NavLink>
          <NavLink to="/settings" className={linkClass}><Settings size={16} /> Settings</NavLink>
        </nav>
      </div>

      <div className="sidebar-footer">
        <button
          className="btn btn-ghost btn-sm"
          style={{ flex: 1, justifyContent: 'flex-start' }}
          onClick={logout}
          title={user ? `Signed in as ${user.login}` : 'Forget the API key held by this tab'}
        >
          <LogOut size={14} /> <span className="truncate">Sign out{user ? ` ${user.login}` : ''}</span>
        </button>
        <button
          className="btn btn-ghost btn-sm btn-icon"
          onClick={toggleTheme}
          title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
        </button>
      </div>
    </aside>
  );
}

function Shell() {
  const location = useLocation();
  // The drawer is open for the page it was opened on, so navigating closes it.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const drawerOpen = openOn === location.pathname;
  const setDrawerOpen = (open: boolean) => setOpenOn(open ? location.pathname : null);

  return (
    <div className="app-layout">
      <header className="topbar">
        <NavLink to="/" className="sidebar-logo"><Logo size={24} /></NavLink>
        <button
          className="btn btn-ghost btn-icon"
          onClick={() => setDrawerOpen(!drawerOpen)}
          aria-label={drawerOpen ? 'Close navigation' : 'Open navigation'}
          aria-expanded={drawerOpen}
        >
          {drawerOpen ? <X size={18} /> : <Menu size={18} />}
        </button>
      </header>
      <div className={`sidebar-scrim ${drawerOpen ? 'open' : ''}`} onClick={() => setDrawerOpen(false)} />
      <Sidebar open={drawerOpen} />
      <main className="main-content">
        <div className="main-inner">
          <AnimatedRoutes />
        </div>
      </main>
    </div>
  );
}

function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <RepoProvider>
          <BrowserRouter>
            <AuthGuard>
              <Shell />
            </AuthGuard>
          </BrowserRouter>
        </RepoProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}

export default App;
