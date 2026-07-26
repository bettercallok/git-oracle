import os
import json
import logging
from aiokafka import AIOKafkaConsumer

logger = logging.getLogger(__name__)

class KafkaEventConsumer:
    def __init__(self, topic: str, group_id: str):
        self.topic = topic
        self.group_id = group_id
        self.bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        
    async def consume(self, message_handler):
        """Starts consuming from Kafka and passes parsed JSON payloads to the handler."""
        import asyncio
        from aiokafka.errors import UnknownTopicOrPartitionError

        while True:
            try:
                consumer = AIOKafkaConsumer(
                    self.topic,
                    bootstrap_servers=self.bootstrap_servers,
                    group_id=self.group_id,
                    value_deserializer=lambda m: json.loads(m.decode('utf-8'))
                )
                await consumer.start()
                break
            except UnknownTopicOrPartitionError:
                logger.warning(f"Topic {self.topic} not found yet. Retrying in 5 seconds...")
                await asyncio.sleep(5)
            except Exception as e:
                logger.error(f"Failed to start consumer: {e}")
                await asyncio.sleep(5)

        logger.info(f"Listening to Kafka topic: {self.topic}")
        try:
            async for msg in consumer:
                payload = msg.value
                logger.debug(f"Received event: {payload}")
                await message_handler(payload)
        finally:
            await consumer.stop()
