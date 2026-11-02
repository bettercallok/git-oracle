"""Kafka topic names the Python agents use — one place, like KafkaTopics.java.

These were string literals repeated across the investigator, planner, fixer and
shared consumer. tests/test_topics_match_java.py checks every name here against
java-backend/git-oracle-core/.../kafka/KafkaTopics.java, so a rename on either
side fails CI instead of silently routing jobs to a topic nobody reads.
"""
JOB_INVESTIGATE = "job.events.investigate"
JOB_PLAN = "job.events.plan"
JOB_FIX = "job.events.fix"
JOB_DLQ = "job.events.dlq"
FIX_GENERATED = "fix-generated"
JOB_ESCALATED = "job-escalated"
