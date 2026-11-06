import type { ReactNode } from 'react';
import { ArrowRight, Code2, Server, Users } from 'lucide-react';

const REPO_URL = 'https://github.com/bettercallok/git-oracle';

const WAYS = [
  { href: '#hosted', icon: <Users size={16} />, title: 'Use a hosted GitOracle', desc: 'Someone already runs it, and you want it to fix bugs in your repositories.' },
  { href: '#self-host', icon: <Server size={16} />, title: 'Run your own copy', desc: 'Fork it to develop on it, or to run it for yourself or your team.' },
  { href: '#jobs', icon: <Code2 size={16} />, title: 'Connect your tools', desc: 'Start jobs from scripts, CI failures or error reports.' },
];

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="card guide-section">
      <h2 className="guide-heading">{title}</h2>
      {children}
    </section>
  );
}

function Code({ children }: { children: string }) {
  return <pre className="code-block guide-code">{children}</pre>;
}

function Inline({ children }: { children: string }) {
  return <code className="inline">{children}</code>;
}

export default function DeveloperGuide() {
  return (
    <div className="guide">
      <header className="page-header">
        <div className="page-header-main">
          <h1 className="page-title">Developer guide</h1>
          <p className="guide-lede">The ways to use GitOracle, and how to get access for each one.</p>
        </div>
      </header>

      <nav className="guide-ways" aria-label="Ways to use GitOracle">
        {WAYS.map(way => (
          <a key={way.href} href={way.href} className="card guide-way">
            <span className="guide-way-title">{way.icon} {way.title} <ArrowRight size={14} className="guide-way-arrow" /></span>
            <span className="guide-way-desc">{way.desc}</span>
          </a>
        ))}
      </nav>

      <Section id="hosted" title="Use a hosted GitOracle">
        <p>Access is by invitation, and you don't need a key.</p>
        <ol className="guide-steps">
          <li>Ask an admin of your organisation to invite your GitHub username. An invitation lasts 7 days.</li>
          <li>Open the dashboard and click <strong>Sign in with GitHub</strong>.</li>
          <li>Jobs only run on repositories on your organisation's list; the person who runs GitOracle adds them for now.</li>
          <li>Go to <strong>Ask to fix</strong> and describe a bug.</li>
        </ol>
      </Section>

      <Section id="self-host" title="Run your own copy">
        <p>
          A fork is completely separate: it has its own database and its own keys, and nobody needs to give you anything.
          You need Java 21, Python 3.11+, Node 18+, Docker and at least 8 GB of RAM.
        </p>
        <ol className="guide-steps">
          <li>
            Fork the repository, clone your fork, and create your settings file:
            <Code>{`git clone https://github.com/<you>/git-oracle.git
cd git-oracle
cp .env.example .env`}</Code>
          </li>
          <li>
            In <Inline>.env</Inline>, set your LLM provider. Any OpenAI-compatible service works, and the URL must be the full chat-completions address:
            <Code>{`LLM_BASE_URL=https://api.groq.com/openai/v1/chat/completions
LLM_MODEL_NAME=<model>
OPENAI_API_KEY=<your provider key>`}</Code>
          </li>
          <li>
            Also in <Inline>.env</Inline>, list the repositories it may work on. An empty list allows none:
            <Code>{`GITORACLE_ALLOWED_REPOS=<you>/<repo>`}</Code>
          </li>
          <li>
            Start it. The first run takes about 10 minutes and creates your secrets for you:
            <Code>{`make infra-up
./start_local.sh`}</Code>
          </li>
          <li>
            Open <Inline>http://localhost:5173</Inline> and sign in with your generated key:
            <Code>{`grep '^GITORACLE_API_KEY=' .env`}</Code>
          </li>
        </ol>
        <p>Stop it with <Inline>./stop_local.sh</Inline>.</p>

        <h3 className="guide-subheading">To use a local LLM</h3>
        <p>
          Point <Inline>LLM_BASE_URL</Inline> at your server's full chat-completions URL, such as{' '}
          <Inline>http://localhost:11434/v1/chat/completions</Inline> for Ollama, and set <Inline>LLM_MODEL_NAME</Inline>.
          Blank <Inline>OPENAI_API_KEY</Inline> so your cloud key isn't sent there, and leave <Inline>LLM_FALLBACK_BASE_URL</Inline> unset
          so your code never goes to the cloud. Restart, then check the <strong>System status</strong> page.
        </p>

        <h3 className="guide-subheading">To open real pull requests</h3>
        <p>
          Create your own GitHub App and install it on your repositories, then set <Inline>GITHUB_APP_ID</Inline>,{' '}
          <Inline>GITHUB_INSTALLATION_ID</Inline> and <Inline>GITHUB_PRIVATE_KEY_PATH</Inline>. Each copy needs its own App.
        </p>

        <h3 className="guide-subheading">To turn on Sign in with GitHub</h3>
        <ol className="guide-steps">
          <li>In your GitHub App's settings, add the Redirect URI <Inline>http://localhost:8080/api/v1/auth/github/callback</Inline> and generate a client secret.</li>
          <li>Set <Inline>GITHUB_APP_CLIENT_ID</Inline> and <Inline>GITHUB_APP_CLIENT_SECRET</Inline>.</li>
          <li>Set <Inline>GITORACLE_ADMIN_GITHUB_IDS</Inline> to your numeric id, the <Inline>id</Inline> at <Inline>{'https://api.github.com/users/<you>'}</Inline>.</li>
          <li>Add anyone else to <Inline>GITORACLE_INVITED_GITHUB_USERS</Inline>, then restart.</li>
        </ol>
        <p>The login page then offers GitHub only, without the API key form.</p>
      </Section>

      <Section id="access" title="Ways to sign in">
        <div className="table-scroll">
          <table className="guide-table">
            <thead><tr><th>Method</th><th>Best for</th><th>How to get it</th></tr></thead>
            <tbody>
              <tr><td><strong>Sign in with GitHub</strong></td><td>People using the dashboard</td><td>An organisation admin invites your GitHub account, with a role: viewer, member, admin or owner.</td></tr>
              <tr><td><strong>API key</strong></td><td>Scripts and CI</td><td>Create one on the <strong>API keys</strong> page after signing in. It's shown only once.</td></tr>
              <tr><td><strong>Bootstrap key</strong></td><td>Your own copy, before GitHub sign-in is set up</td><td><Inline>GITORACLE_API_KEY</Inline> in your <Inline>.env</Inline>. Turn it off before hosting.</td></tr>
            </tbody>
          </table>
        </div>
        <p>
          API keys are for code, not for signing in to the dashboard. They can be read-only or read and write, always expire, can't do admin work, and can't create other keys.
          Keep them out of the repository and out of chat. If one leaks, revoke it on the same page; it stops working within a minute.
        </p>
      </Section>

      <Section id="jobs" title="Ways to start a job">
        <h3 className="guide-subheading">Dashboard</h3>
        <p>Use <strong>Ask to fix</strong> to describe a bug in plain English and choose whether it investigates first.</p>

        <h3 className="guide-subheading">HTTP API</h3>
        <Code>{`curl -X POST http://localhost:8080/api/v1/trigger \\
  -H "X-API-Key: $GITORACLE_KEY" -H 'Content-Type: application/json' \\
  -d '{"repoUrl":"https://github.com/<owner>/<repo>",
       "issueDescription":"Checkout crashes when the cart is empty"}'`}</Code>
        <p>
          It investigates the cause first by default. Add <Inline>{'"investigateFirst": false'}</Inline> to skip that when you already know the fix,
          or <Inline>"branch"</Inline> to work on a specific branch. Follow progress with <Inline>{'GET /api/v1/jobs/<id>'}</Inline>.
        </p>

        <h3 className="guide-subheading">GitHub webhook</h3>
        <p>
          Point your GitHub App's webhook at <Inline>/webhook/github</Inline> and set the same secret in <Inline>GITHUB_APP_WEBHOOK_SECRET</Inline>.
          Failed <Inline>workflow_run</Inline> events start a job, and PR comments can start another fix cycle.
          The server must be reachable from the internet, so this doesn't work on plain localhost.
        </p>

        <h3 className="guide-subheading">Sentry webhook</h3>
        <p>
          Send reports to <Inline>{'/webhook/<tenant id>/sentry'}</Inline>, signed with the tenant's secret. An admin sets it with{' '}
          <Inline>{'POST /api/v1/admin/tenants/<id>/sentry-secret'}</Inline>.
        </p>

        <h3 className="guide-subheading">Webhook simulator</h3>
        <p>
          Admins can use this dashboard page to simulate a failed Actions run and test the whole pipeline. It starts a real job on an allowed repository.
        </p>

        <h3 className="guide-subheading">Command line</h3>
        <p>Not ready yet: the <Inline>gitoracle</Inline> CLI doesn't send an API key, so its API calls are rejected. Use the HTTP API for now.</p>
      </Section>

      <Section id="contributing" title="Work on the code">
        <p>Run the same checks as CI before opening a pull request:</p>
        <Code>{`# Java
cd java-backend && ./gradlew test --no-daemon

# Python (from the repository root)
source ai_core/.venv/bin/activate
ruff check ai_core cli/gitOracle eval --select E9,F
pytest ai_core/tests/

# Dashboard
cd dashboard && npm run lint && npm run build`}</Code>
        <p>
          No test calls a real LLM. For the architecture and project rules, read the{' '}
          <a href={`${REPO_URL}#readme`} target="_blank" rel="noreferrer">README</a> and{' '}
          <a href={`${REPO_URL}/blob/main/CLAUDE.md`} target="_blank" rel="noreferrer">CLAUDE.md</a>.
        </p>
      </Section>
    </div>
  );
}
