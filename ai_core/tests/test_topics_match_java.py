"""
Every Kafka topic the Python agents use must exist, under the same name, in
KafkaTopics.java. The two sides only meet at runtime on the broker, so a
rename on one side used to route jobs into a topic nobody consumed, with no
error anywhere — the job just never moved.
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared import topics  # noqa: E402

KAFKA_TOPICS_JAVA = os.path.join(
    os.path.dirname(__file__), "..", "..", "java-backend", "git-oracle-core", "src", "main", "java",
    "ai", "gitoracle", "core", "kafka", "KafkaTopics.java",
)


def _java_topics() -> dict:
    with open(KAFKA_TOPICS_JAVA) as f:
        return dict(re.findall(r'public static final String (\w+)\s*=\s*"([^"]+)"', f.read()))


def _python_topics() -> dict:
    return {name: value for name, value in vars(topics).items() if name.isupper()}


def test_every_python_topic_is_declared_in_KafkaTopics_java_with_the_same_value():
    java = _java_topics()
    python = _python_topics()
    assert python, "shared/topics.py declares no topics"
    for name, value in python.items():
        assert name in java, f"{name} is in shared/topics.py but not in KafkaTopics.java"
        assert java[name] == value, f"{name}: Python says {value!r}, Java says {java[name]!r}"


def test_the_java_file_is_actually_parsed():
    # Guards the test itself: a moved file or changed declaration style must
    # fail loudly here, not make the check above vacuously pass.
    assert _java_topics().get("JOB_FIX") == "job.events.fix"
