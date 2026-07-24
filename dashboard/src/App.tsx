import { BrowserRouter, Routes, Route, NavLink, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Activity, ShieldAlert, GitMerge, FileWarning, BarChart2, BrainCircuit, Settings } from 'lucide-react';
import JobFeed from './pages/JobFeed';
import JobDetail from './pages/JobDetail';
import EscalationQueue from './pages/EscalationQueue';
import RiskHeatmap from './pages/RiskHeatmap';
import EvalDashboard from './pages/EvalDashboard';
import LangfuseEmbed from './pages/LangfuseEmbed';
import AdminSettings from './pages/AdminSettings';

// Page wrapper for animations
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
        <Route path="/" element={<PageWrapper><JobFeed /></PageWrapper>} />
        <Route path="/job/:jobId" element={<PageWrapper><JobDetail /></PageWrapper>} />
        <Route path="/escalations" element={<PageWrapper><EscalationQueue /></PageWrapper>} />
        <Route path="/risk" element={<PageWrapper><RiskHeatmap /></PageWrapper>} />
        <Route path="/eval" element={<PageWrapper><EvalDashboard /></PageWrapper>} />
        <Route path="/langfuse" element={<PageWrapper><LangfuseEmbed /></PageWrapper>} />
        <Route path="/settings" element={<PageWrapper><AdminSettings /></PageWrapper>} />
      </Routes>
    </AnimatePresence>
  );
}

function App() {
  return (
    <BrowserRouter>
      <div className="app-layout">
        <aside className="sidebar">
          <NavLink to="/" className="sidebar-logo">
            <Activity size={26} color="var(--accent)" /> Git<span>Oracle</span>
          </NavLink>
          
          <nav className="sidebar-nav" style={{ marginTop: 24 }}>
            <NavLink to="/" end className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <GitMerge size={16} /> Job Feed
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
    </BrowserRouter>
  );
}

export default App;
