# One image for all seven Python agents; AGENT picks which one runs.
#   docker build -t gitoracle/agents ai_core
#   docker run -e AGENT=fixer gitoracle/agents
FROM python:3.11-slim

# git: the agents read history and diffs from cloned repos (GitPython shells out to it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY agents ./agents
COPY shared ./shared
COPY run-agent.sh ./run-agent.sh

# Listen on every interface inside the container; the cluster's NetworkPolicy
# and the internal token decide who can call it.
ENV BIND_HOST=0.0.0.0 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN useradd --uid 10001 --create-home agent && chmod 0555 run-agent.sh
USER 10001

ENTRYPOINT ["/app/run-agent.sh"]
