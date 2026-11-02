"""
The consumer loop must survive what killed it in the first CI eval run.

That run's investigator raised on its first message (lz4 codec missing); the
exception left `async for`, the background task ended, nothing restarted it,
and every job waited ten minutes for a timeout. These tests drive the loop with
a fake aiokafka consumer and a fake producer — no broker needed.
"""
import asyncio
import json
import os
import sys
from types import SimpleNamespace

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import shared.kafka_consumer as kc  # noqa: E402


class FakeProducer:
    def __init__(self):
        self.published = []

    async def publish(self, topic, payload):
        self.published.append((topic, payload))


class FakeAioConsumer:
    """Yields the given raw messages, then raises CancelledError to end the test."""

    def __init__(self, messages, fail_on_start=False):
        self._messages = list(messages)
        self._fail_on_start = fail_on_start
        self.stopped = False

    async def start(self):
        if self._fail_on_start:
            raise ConnectionError("broker unreachable")

    async def stop(self):
        self.stopped = True

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self._messages:
            return SimpleNamespace(value=self._messages.pop(0))
        raise asyncio.CancelledError()


def _raw(payload):
    return json.dumps(payload).encode()


@pytest.fixture
def producer(monkeypatch):
    p = FakeProducer()
    monkeypatch.setattr(kc, "get_producer", lambda: p)
    return p


def _consumer(fakes):
    c = kc.KafkaEventConsumer("job.events.fix", "fixer-agent-group", initial_backoff=0, max_backoff=0)
    c._make_consumer = lambda: fakes.pop(0)
    return c


def test_a_failing_message_is_dead_lettered_escalated_and_the_next_one_still_runs(producer):
    handled = []

    async def handler(payload):
        if payload["job_id"] == "job-1":
            raise ValueError("bad plan")
        handled.append(payload["job_id"])

    consumer = _consumer([FakeAioConsumer([_raw({"job_id": "job-1"}), _raw({"job_id": "job-2"})])])
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(consumer.consume(handler))

    assert handled == ["job-2"]
    topics = [t for t, _ in producer.published]
    assert topics == ["job.events.dlq", "job-escalated"]
    dlq = producer.published[0][1]
    assert dlq["jobId"] == "job-1" and "bad plan" in dlq["error"] and dlq["payload"] == {"job_id": "job-1"}
    escalation = producer.published[1][1]
    assert escalation["jobId"] == "job-1"
    assert escalation["source"] == "fixer"
    assert "bad plan" in escalation["reason"]


def test_an_undecodable_message_is_dead_lettered_without_calling_the_handler(producer):
    calls = []

    async def handler(payload):
        calls.append(payload)

    consumer = _consumer([FakeAioConsumer([b"\xff not json", _raw({"job_id": "job-2"})])])
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(consumer.consume(handler))

    assert calls == [{"job_id": "job-2"}]
    assert [t for t, _ in producer.published] == ["job.events.dlq"]   # no job id -> nothing to escalate


def test_empty_and_non_object_messages_are_dead_lettered_without_calling_the_handler(producer):
    calls = []

    async def handler(payload):
        calls.append(payload)

    # None is a tombstone (no body); the rest are valid JSON but not an event object.
    raws = [None, _raw([1, 2]), _raw("text"), _raw(3), _raw({"job_id": "job-4"})]
    consumer = _consumer([FakeAioConsumer(raws)])
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(consumer.consume(handler))

    assert calls == [{"job_id": "job-4"}]
    assert [t for t, _ in producer.published] == ["job.events.dlq"] * 4
    assert [p["payload"] for _, p in producer.published] == [None, [1, 2], "text", 3]
    assert all(p["error"].startswith("not a JSON object") for _, p in producer.published)


def test_a_consumer_that_dies_is_rebuilt_instead_of_ending_the_task(producer):
    handled = []

    async def handler(payload):
        handled.append(payload["job_id"])

    dead = FakeAioConsumer([], fail_on_start=True)
    healthy = FakeAioConsumer([_raw({"job_id": "job-3"})])
    consumer = _consumer([dead, healthy])
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(consumer.consume(handler))

    assert handled == ["job-3"]
    assert consumer.restarts == 1
    assert dead.stopped is True       # the dead consumer is cleaned up before the rebuild
    assert consumer.alive is False    # cancelled -> no longer alive


def test_jobId_and_job_id_are_both_recognised():
    assert kc._job_id({"job_id": "a"}) == "a"
    assert kc._job_id({"jobId": "b"}) == "b"
    assert kc._job_id({"other": 1}) is None
    assert kc._job_id("not a dict") is None
