import pytest
import os

@pytest.fixture(autouse=True)
def mock_env_vars():
    """Keep tests off real services. (LLM_SERVER_URL used to be set here too; it
    was read only by the dead shared/llm_client.py, so it never pointed any agent
    at a mock.)"""
    os.environ["QDRANT_URL"] = "http://localhost:6333"
    yield
