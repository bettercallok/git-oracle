"""
How llm_structured handles a provider refusing a JSON-mode request.

Groq validates JSON-mode replies itself and answers 400 json_validate_failed
when the model's output isn't valid JSON — most often because max_tokens cut
it off. That reached the dashboard as httpx's "Client error '400 Bad Request'
... developer.mozilla.org" with the provider's explanation thrown away (the
Commit Analyst failing on "What tests should I write for this?").
"""
import asyncio
import json
import os
import sys

import httpx
import pytest
from pydantic import BaseModel

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from shared import structured_output  # noqa: E402
from shared.structured_output import LLMRequestError  # noqa: E402

URL = "http://llm.test/v1/chat/completions"


class Answer(BaseModel):
    answer: str


def _ok(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}], "usage": {"total_tokens": 5}},
                          request=httpx.Request("POST", URL))


def _groq_json_failure(failed_generation: str) -> httpx.Response:
    return httpx.Response(400, json={"error": {
        "message": "Failed to generate JSON. Please adjust your prompt. See 'failed_generation' for more details.",
        "type": "invalid_request_error", "code": "json_validate_failed",
        "failed_generation": failed_generation}}, request=httpx.Request("POST", URL))


@pytest.fixture
def replies(monkeypatch):
    """Queue provider responses; records the max_tokens each request asked for."""
    queue: list[httpx.Response] = []
    asked: list[int] = []

    async def fake_execute(client, base_url, payload, headers=None):
        asked.append(payload["max_tokens"])
        return queue.pop(0)

    monkeypatch.setattr(structured_output, "_execute_with_rate_limit_backoff", fake_execute)
    monkeypatch.setattr(structured_output, "_lf", None)
    return queue, asked


def _call(max_tokens=1024):
    return asyncio.run(structured_output.llm_structured(
        messages=[{"role": "user", "content": "What tests should I write for this?"}],
        output_schema=Answer, max_tokens=max_tokens, base_url=URL, agent_name="commit_analyst"))


def test_a_reply_cut_off_mid_json_is_retried_once_with_more_room(replies):
    queue, asked = replies
    queue += [_groq_json_failure('{"answer": "Write tests for the null guard in Use'), _ok('{"answer": "done"}')]

    assert _call().answer == "done"
    assert asked == [1024, 2048]


def test_valid_json_wrapped_in_a_fence_is_recovered_without_another_call(replies):
    queue, asked = replies
    queue.append(_groq_json_failure('```json\n{"answer": "Test UserService.pay with a null user."}\n```'))

    assert _call().answer == "Test UserService.pay with a null user."
    assert asked == [1024]


def test_still_invalid_after_the_retry_says_so_in_the_providers_words(replies):
    queue, _ = replies
    queue += [_groq_json_failure('{"answer": "cut'), _groq_json_failure('{"answer": "cut again')]

    with pytest.raises(LLMRequestError) as e:
        _call()
    msg = str(e.value)
    assert "Failed to generate JSON" in msg and "json_validate_failed" in msg and "max_tokens=2048" in msg
    assert "mozilla" not in msg


def test_any_other_4xx_carries_the_providers_message(replies):
    queue, asked = replies
    queue.append(httpx.Response(400, json={"error": {
        "message": "The model `llama-9000` does not exist", "code": "model_not_found"}},
        request=httpx.Request("POST", URL)))

    with pytest.raises(LLMRequestError) as e:
        _call()
    assert e.value.status_code == 400
    assert "does not exist" in str(e.value) and "[model_not_found]" in str(e.value)
    assert asked == [1024]   # not a truncation, so no retry


def test_no_retry_beyond_the_ceiling(replies, monkeypatch):
    queue, asked = replies
    monkeypatch.setattr(structured_output, "LLM_MAX_TOKENS_CEILING", 1024)
    queue.append(_groq_json_failure('{"answer": "cut'))

    with pytest.raises(LLMRequestError):
        _call()
    assert asked == [1024]


def test_recovered_reply_must_still_match_the_schema(replies):
    queue, _ = replies
    queue.append(_groq_json_failure(json.dumps({"wrong": 1})))
    with pytest.raises(Exception) as e:
        _call()
    assert "answer" in str(e.value)
