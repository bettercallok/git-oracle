import { BrowserRouter, Routes, Route, NavLink, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Activity, ShieldAlert, GitMerge, FileWarning, BarChart2, BrainCircuit, Settings, TerminalSquare, Wand2, GitFork, GitCommit } from 'lucide-react';
import JobFeed from './pages/JobFeed';
import JobDetail from './pages/JobDetail';
import EscalationQueue from './pages/EscalationQueue';
import RiskHeatmap from './pages/RiskHeatmap';
import EvalDashboard from './pages/EvalDashboard';
import LangfuseEmbed from './pages/LangfuseEmbed';
import AdminSettings from './pages/AdminSettings';
import Simulator from './pages/Simulator';
import FixCommand from './pages/FixCommand';
import RepoManager from './pages/RepoManager';
import CommitExplorer from './pages/CommitExplorer';
import CommitDetail from './pages/CommitDetail';
import Login from './pages/Login';
import { RepoProvider, useRepos } from './context/RepoContext';
import { AuthProvider, useAuth } from './context/AuthContext';

function AuthGuard({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated && location.pathname !== '/login') {
    return <Login />;
  }
  
  if (isAuthenticated && location.pathname === '/login') {
    window.location.href = '/';
    return null;
  }

  return <>{children}</>;
}

function PageWrapper({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -10 }}
      transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </motion.div>
  );
}

function AnimatedRoutes() {
  const location = useLocation();
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
  if (!activeRepo) return (
    <NavLink to="/repos" style={{ textDecoration: 'none' }}>
      <div style={{
        margin: '12px 12px 0', padding: '8px 12px', borderRadius: 8,
        background: 'rgba(99,102,241,0.08)', border: '1px dashed rgba(99,102,241,0.3)',
        fontSize: '0.75rem', color: 'var(--text-muted)', cursor: 'pointer',
        display: 'flex', alignItems: 'center', gap: 6
      }}>
        <GitFork size={12} /> Add a repo to get started
      </div>
    </NavLink>
  );
  return (
    <NavLink to="/repos" style={{ textDecoration: 'none' }}>
      <div style={{
        margin: '12px 12px 0', padding: '8px 12px', borderRadius: 8,
        background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.25)',
        cursor: 'pointer', transition: 'background 0.2s'
      }}>
        <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 3 }}>
          Active Repo
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--success)', boxShadow: '0 0 6px var(--success)' }} />
          <span style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {activeRepo.fullName}
          </span>
        </div>
      </div>
    </NavLink>
  );
}

function App() {
  return (
    <AuthProvider>
      <RepoProvider>
        <BrowserRouter>
          <AuthGuard>
            <div className="app-layout">
          <aside className="sidebar">
            <NavLink to="/" className="sidebar-logo">
              <Activity size={26} color="var(--accent)" /> Git<span>Oracle</span>
            </NavLink>

            <ActiveRepoPill />

            <nav className="sidebar-nav" style={{ marginTop: 16 }}>
              <NavLink to="/" end className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <GitMerge size={16} /> Job Feed
              </NavLink>
              <NavLink to="/fix" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                style={({ isActive }) => isActive ? {} : { background: 'linear-gradient(90deg,rgba(99,102,241,0.08),transparent)', borderLeft: '2px solid var(--accent)', paddingLeft: 10 }}
              >
                <Wand2 size={16} /> Ask to Fix
              </NavLink>
              <NavLink to="/repos" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <GitFork size={16} /> My Repos
              </NavLink>
              <NavLink to="/commits" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <GitCommit size={16} /> Commit Explorer
              </NavLink>
              <NavLink to="/escalations" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <ShieldAlert size={16} /> Escalation Queue
              </NavLink>
              <NavLink to="/risk" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <FileWarning size={16} /> Risk Heatmap
              </NavLink>
              <NavLink to="/eval" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <BarChart2 size={16} /> Eval Dashboard
              </NavLink>
              <NavLink to="/langfuse" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <BrainCircuit size={16} /> Langfuse Traces
              </NavLink>
              <NavLink to="/simulator" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <TerminalSquare size={16} /> Simulator
              </NavLink>
              <div style={{ margin: '16px 0', height: 1, background: 'var(--border-subtle)' }} />
              <NavLink to="/settings" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <Settings size={16} /> Admin Settings
              </NavLink>
            </nav>
          </aside>

              <main className="main-content">
                <AnimatedRoutes />
              </main>
            </div>
          </AuthGuard>
        </BrowserRouter>
      </RepoProvider>
    </AuthProvider>
  );
}

export default App;
