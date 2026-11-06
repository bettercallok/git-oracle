"""
A misconfigured agent must refuse to start with a message naming the setting,
instead of starting and failing every request later (401s for a missing token,
404s for an LLM_BASE_URL set to the API root as .env.example used to show).
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.config import check_startup_config, llm_url_problem  # noqa: E402

GOOD_URL = "https://api.groq.com/openai/v1/chat/completions"


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "tok")
    monkeypatch.setenv("LLM_BASE_URL", GOOD_URL)


def test_a_complete_config_starts():
    check_startup_config("fixer", needs_llm=True)


def test_a_missing_internal_token_stops_startup(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "  ")
    with pytest.raises(SystemExit, match="GITORACLE_INTERNAL_TOKEN is not set"):
        check_startup_config("guardrails", needs_llm=False)


def test_the_api_root_is_rejected_as_an_llm_url(monkeypatch):
    monkeypatch.setenv("LLM_BASE_URL", "https://api.openai.com/v1")
    with pytest.raises(SystemExit, match="full chat-completions URL"):
        check_startup_config("planner", needs_llm=True)


def test_agents_that_dont_call_the_llm_dont_need_its_url(monkeypatch):
    monkeypatch.delenv("LLM_BASE_URL")
    check_startup_config("prompt_registry", needs_llm=False)


def test_llm_url_rules():
    assert llm_url_problem(GOOD_URL) is None
    assert llm_url_problem(GOOD_URL + "/") is None
    assert "http(s)" in llm_url_problem("api.groq.com/openai/v1/chat/completions")


def test_a_malformed_embeddings_url_stops_startup(monkeypatch):
    monkeypatch.setenv("EMBEDDINGS_BASE_URL", "localhost:11434/v1/embeddings")
    with pytest.raises(SystemExit, match="EMBEDDINGS_BASE_URL"):
        check_startup_config("fixer", needs_llm=True)


def test_an_empty_embeddings_url_just_turns_memory_off(monkeypatch):
    monkeypatch.setenv("EMBEDDINGS_BASE_URL", "")
    check_startup_config("fixer", needs_llm=True)


@pytest.mark.parametrize("placeholder", ["change_me_internal_service_token", "your_internal_token"])
def test_a_placeholder_internal_token_is_refused(monkeypatch, placeholder):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", placeholder)
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example/v1/chat/completions")

    with pytest.raises(SystemExit) as exit_info:
        check_startup_config("fixer", needs_llm=True)

    assert "placeholder" in str(exit_info.value)
