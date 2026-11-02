"""
The agents are internal: only the orchestrator and other agents call them,
server to server. Each used to install CORSMiddleware with allow_origins=["*"]
and allow_credentials=True, so any page open in a developer's browser could
call an agent on localhost. Browsers never need to reach an agent, so none of
them may have CORS middleware again.
"""
import importlib.util
import sys
from pathlib import Path

from starlette.middleware.cors import CORSMiddleware

AGENTS = Path(__file__).resolve().parent.parent / "agents"


def test_no_agent_installs_cors_middleware():
    sys.path.insert(0, str(AGENTS.parent))
    for main in sorted(AGENTS.glob("*/main.py")):
        # Some agents import sibling modules by bare name (guardrails).
        sys.path.insert(0, str(main.parent))
        spec = importlib.util.spec_from_file_location(f"cors_check_{main.parent.name}", main)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        classes = [m.cls for m in module.app.user_middleware]
        assert CORSMiddleware not in classes, f"{main.parent.name} installs CORSMiddleware"
