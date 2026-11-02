"""Run an agent's Kafka consumer for exactly as long as its FastAPI app.

Replaces the @app.on_event("startup"/"shutdown") pair each Kafka agent had
(deprecated in FastAPI — every agent logged a DeprecationWarning at boot). The
app object is created at the top of each agent while its consumer and handler
are defined at the bottom, so this installs the lifespan on the existing app
rather than passing lifespan= to FastAPI(...).
"""
import asyncio
import contextlib
from typing import Any, Awaitable, Callable

from fastapi import FastAPI

from shared.kafka_consumer import KafkaEventConsumer
from shared.kafka_producer import close_producer


def run_consumer_with_app(app: FastAPI, consumer: KafkaEventConsumer,
                          handler: Callable[[dict], Awaitable[Any]]) -> None:
    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI):
        # Kept on app.state: asyncio holds only a weak reference to tasks.
        _app.state.consumer_task = asyncio.create_task(consumer.consume(handler))
        try:
            yield
        finally:
            _app.state.consumer_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await _app.state.consumer_task
            await close_producer()

    app.router.lifespan_context = lifespan
