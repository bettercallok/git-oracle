"""Startup configuration checks for the agents.

Every setting used to be read lazily with a default, so a misconfigured agent
started fine and failed later, one request at a time, far from the cause: no
GITORACLE_INTERNAL_TOKEN meant every call to it got 401; an LLM_BASE_URL set to
the API root (".../v1", as .env.example used to show) meant every LLM call 404'd.

check_startup_config() runs from each agent's __main__ block — so starting a
misconfigured agent exits immediately with a message naming the setting, while
tests that merely import an agent module are unaffected.
"""
import os
import sys
from typing import Iterable, List


def missing(names: Iterable[str]) -> List[str]:
    return [n for n in names if not os.environ.get(n, "").strip()]


def llm_url_problem(url: str) -> str | None:
    if not url.startswith(("http://", "https://")):
        return f"LLM_BASE_URL must be an http(s) URL, got {url!r}"
    if not url.rstrip("/").endswith("/chat/completions"):
        return (f"LLM_BASE_URL must be the full chat-completions URL "
                f"(e.g. https://api.groq.com/openai/v1/chat/completions), got {url!r}")
    return None


def check_startup_config(agent: str, *, needs_llm: bool) -> None:
    """Exit the process with a clear message if required settings are missing."""
    problems = [f"{n} is not set" for n in missing(["GITORACLE_INTERNAL_TOKEN"])]
    if needs_llm:
        if missing(["LLM_BASE_URL"]):
            problems.append("LLM_BASE_URL is not set")
        else:
            problem = llm_url_problem(os.environ["LLM_BASE_URL"].strip())
            if problem:
                problems.append(problem)
    if problems:
        sys.exit(f"{agent}: refusing to start — " + "; ".join(problems) + ". Set it in the root .env.")
