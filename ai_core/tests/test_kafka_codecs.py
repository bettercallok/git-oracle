"""The Kafka broker stores every topic lz4-compressed (docker-compose.infra.yml
KAFKA_COMPRESSION_TYPE, infrastructure/kafka/create-topics.sh), whatever the
producer sent. If aiokafka can't decode lz4, an agent's consumer task dies on its
first message with UnsupportedCodecError, and every job sits in INVESTIGATING
until it times out. That happened on the first CI eval run; a dev venv that
happened to have lz4 installed had hidden it.
"""
from aiokafka.codec import has_lz4


def test_lz4_codec_available():
    assert has_lz4(), "lz4 is missing: add it to ai_core/requirements.txt"
