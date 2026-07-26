import os
import json
import logging
from aiokafka import AIOKafkaProducer

logger = logging.getLogger(__name__)

class KafkaEventProducer:
    def __init__(self):
        self.bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        self.producer = None
        
    async def _get_producer(self):
        if self.producer is None:
            self.producer = AIOKafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                value_serializer=lambda m: json.dumps(m).encode('utf-8')
            )
            await self.producer.start()
        return self.producer

    async def publish(self, topic: str, payload: dict):
        """Publishes a JSON payload to a Kafka topic."""
        producer = await self._get_producer()
        await producer.send_and_wait(topic, payload)
        logger.debug(f"Published event to {topic}: {payload}")
        
    async def stop(self):
        """Stops the producer."""
        if self.producer:
            await self.producer.stop()
            self.producer = None
