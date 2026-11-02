"""Kafka consumer loop shared by the agents that listen on a topic.

The first CI eval run stalled because this loop had no error handling: the
investigator's first message raised (UnsupportedCodecError — lz4 wasn't
installed), the exception left `async for`, `finally` stopped the consumer, and
the background task ended. Nothing restarted it, nothing reported it, the agent's
HTTP server stayed up, and every job waited ten minutes for a timeout.

Now:
  * A message the handler can't process is logged with its traceback, copied
    to the dead-letter topic with the error, and — if it names a job — that job
    is escalated with the reason. The loop moves on to the next message.
  * If the consumer itself dies (broker gone, a codec error while fetching),
    it is rebuilt after a backoff (5s doubling to 60s), forever, loudly.
  * `alive` / `last_message_at` feed the agent's /health endpoint, so a dead
    consumer is visible from outside instead of hiding behind a healthy server.
"""
import asyncio
import json
import logging
import os
import time
from typing import Any, Awaitable, Callable, Optional

from aiokafka import AIOKafkaConsumer

from shared import topics
from shared.kafka_producer import get_producer

logger = logging.getLogger(__name__)

DLQ_TOPIC = topics.JOB_DLQ
ESCALATION_TOPIC = topics.JOB_ESCALATED

Handler = Callable[[dict], Awaitable[Any]]


def _job_id(payload: Any) -> Optional[str]:
    # The job.events.* payloads use job_id; fix-generated/job-escalated use jobId.
    if isinstance(payload, dict):
        value = payload.get("job_id") or payload.get("jobId")
        return str(value) if value else None
    return None


class KafkaEventConsumer:
    def __init__(self, topic: str, group_id: str, *, agent_name: Optional[str] = None,
                 initial_backoff: float = 5.0, max_backoff: float = 60.0):
        self.topic = topic
        self.group_id = group_id
        self.agent_name = agent_name or group_id.removesuffix("-agent-group")
        kafka_host = os.getenv("KAFKA_HOST", "127.0.0.1")
        kafka_port = os.getenv("KAFKA_PORT", "9092")
        self.bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", f"{kafka_host}:{kafka_port}")
        self.initial_backoff = initial_backoff
        self.max_backoff = max_backoff
        self.alive = False
        self.last_message_at: Optional[float] = None
        self.restarts = 0

    def _make_consumer(self) -> AIOKafkaConsumer:
        return AIOKafkaConsumer(
            self.topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.group_id,
            # "latest" (aiokafka's default) silently skipped anything published
            # before this group's first start — e.g. a job dispatched while the
            # agent was still booting. Only matters for a brand-new group; after
            # that the committed offset wins.
            auto_offset_reset="earliest",
            # Raw bytes: decoding happens per message in _handle, so one
            # malformed message can be dead-lettered instead of killing the loop.
        )

    async def consume(self, message_handler: Handler) -> None:
        """Runs until cancelled. Never returns or raises otherwise."""
        delay = self.initial_backoff
        while True:
            consumer = None
            try:
                consumer = self._make_consumer()
                await consumer.start()
                self.alive = True
                delay = self.initial_backoff
                logger.info(f"Listening to Kafka topic: {self.topic}")
                async for msg in consumer:
                    self.last_message_at = time.time()
                    await self._handle(msg.value, message_handler)
                raise RuntimeError("consumer iterator ended unexpectedly")
            except asyncio.CancelledError:
                raise
            except Exception as e:
                self.restarts += 1
                logger.exception(f"Kafka consumer on {self.topic} died ({e!r}); "
                                 f"restarting in {delay:.0f}s (restart #{self.restarts})")
            finally:
                self.alive = False
                if consumer is not None:
                    try:
                        await consumer.stop()
                    except Exception:  # noqa: BLE001 — cleanup of a consumer that has already died
                        logger.debug("Error stopping dead consumer", exc_info=True)
            await asyncio.sleep(delay)
            delay = min(delay * 2, self.max_backoff)

    async def _handle(self, raw: Optional[bytes], message_handler: Handler) -> None:
        try:
            payload = json.loads(raw.decode("utf-8")) if raw is not None else None
        except ValueError as e:  # bad UTF-8 or bad JSON (both ValueError)
            logger.error(f"Undecodable message on {self.topic}: {e}")
            await self._dead_letter(raw.decode("utf-8", errors="replace") if raw else None,
                                    f"undecodable message: {e}", None)
            return

        # Every handler expects an event object. An empty message (raw None — a
        # tombstone) or valid JSON that isn't an object (`[]`, `"x"`, `3`) used to
        # reach the handler anyway and crash it with an unrelated TypeError.
        if not isinstance(payload, dict):
            logger.error(f"Non-object message on {self.topic}: {type(payload).__name__}")
            await self._dead_letter(payload, f"not a JSON object: {type(payload).__name__}", None)
            return

        logger.debug(f"Received event: {payload}")
        try:
            await message_handler(payload)
        except Exception as e:
            job_id = _job_id(payload)
            logger.exception(f"{self.agent_name} failed on a {self.topic} message (job {job_id})")
            await self._dead_letter(payload, repr(e), job_id)
            if job_id:
                await self._escalate(job_id, f"{self.agent_name} crashed while handling the job: {e}")

    async def _dead_letter(self, payload: Any, error: str, job_id: Optional[str]) -> None:
        try:
            await get_producer().publish(DLQ_TOPIC, {
                "topic": self.topic,
                "agent": self.agent_name,
                "jobId": job_id,
                "error": error[:4000],
                "payload": payload,
                "failedAt": time.time(),
            })
        except Exception:
            logger.exception(f"Could not dead-letter a {self.topic} message")

    async def _escalate(self, job_id: str, reason: str) -> None:
        try:
            await get_producer().publish(ESCALATION_TOPIC, {
                "jobId": job_id,
                "reason": reason[:4000],
                # No confidenceScore: a crash has none, and a placeholder 0.0
                # showed in the Escalation Queue as a real, very low score.
                "source": self.agent_name,
            })
        except Exception:
            logger.exception(f"Could not escalate job {job_id}")
