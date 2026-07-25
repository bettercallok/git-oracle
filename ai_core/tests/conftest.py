import pytest
import os

@pytest.fixture(autouse=True)
def mock_env_vars():
    """Force all agent tests to use the Mock LLM server instead of production."""
    os.environ["LLM_SERVER_URL"] = "http://127.0.0.1:8089/v1"
    os.environ["QDRANT_URL"] = "http://localhost:6333"
    yield
    # Cleanup (handled automatically by pytest)
