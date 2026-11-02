"""
run_consumer_with_app must start the agent's consumer when the app starts and
cancel it (and close the shared producer) when the app stops — what the
deprecated on_event("startup"/"shutdown") pair used to do.
"""
import asyncio
import os
import sys

from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import shared.consumer_lifespan as cl  # noqa: E402


class FakeConsumer:
    def __init__(self):
        self.started = False
        self.cancelled = False

    async def consume(self, handler):
        self.started = True
        try:
            await asyncio.Event().wait()   # runs until cancelled
        except asyncio.CancelledError:
            self.cancelled = True
            raise


def test_the_consumer_lives_exactly_as_long_as_the_app(monkeypatch):
    closed = []

    async def fake_close():
        closed.append(True)

    monkeypatch.setattr(cl, "close_producer", fake_close)
    app, consumer = FastAPI(), FakeConsumer()
    cl.run_consumer_with_app(app, consumer, lambda payload: None)

    with TestClient(app):
        assert consumer.started
        assert not consumer.cancelled
    assert consumer.cancelled
    assert closed == [True]
