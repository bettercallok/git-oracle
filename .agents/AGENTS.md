# GitOracle Agent Rules

These rules apply specifically to the GitOracle project workspace. They dictate the architectural and stylistic constraints for this project.

## Technology Stack Constraints
- **Java Backend:** Use Java 21, Spring Boot 3.2.x, and Gradle 8.7. All Java services must be multi-module subprojects under the `java-backend` directory.
- **Python AI Core:** Use Python 3.11+, FastAPI, and standard Pydantic validation.
- **Infrastructure:** All backing services (PostgreSQL, Neo4j, Qdrant, Redis, Kafka) must run via Docker Compose.
- **LLM:** Use local `llama.cpp` server (specifically configured for Qwen2.5-Coder); do not integrate third-party APIs (OpenAI/Anthropic) for core code generation to maintain security.

## Architectural Patterns
- **Event-Driven Microservices:** Prefer Kafka topics for inter-service communication over direct REST calls to maintain resilience and speed.
- **Shared Library:** Always use the `git-oracle-core` module for shared JPA models, Kafka topic constants, and Event DTOs to avoid code duplication.
- **No Hardcoded Prompts:** All agent prompts must be fetched dynamically from the Prompt Registry PostgreSQL database. Do not hardcode instructions in Python strings.
- **Security First:** All LLM outputs must be validated by the Guardrails service before executing or pushing code.

## Git & Workflow
- **Commit History:** Keep commits atomic, well-described, and logically grouped (e.g., `feat(core): ...`).

## AI Context Management

- **Retrieval First:** Offload massive datasets (like git histories or large error stacks) to Qdrant/pgvector and retrieve only the top-K most relevant snippets. Ensure the context window (`ctx-size 8192`) is rigorously respected.
