import asyncio
import logging
import sys
import os

sys.path.append(os.path.dirname(os.path.dirname(__file__)))
from shared.kafka_consumer import KafkaEventConsumer
from shared.feedback_processor import process_feedback

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def handle_feedback_event(payload: dict):
    job_id = payload.get("job_id")
    outcome = payload.get("outcome")
    
    if not job_id or not outcome:
        logger.error(f"Invalid feedback payload: {payload}")
        return
        
    await process_feedback(job_id, outcome)

async def main():
    logger.info("Starting GitOracle Feedback Daemon")
    consumer = KafkaEventConsumer(topic="feedback-received", group_id="feedback-daemon-group")
    await consumer.consume(handle_feedback_event)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Feedback Daemon shutting down")
