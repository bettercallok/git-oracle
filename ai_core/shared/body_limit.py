"""
Request body size limit for the FastAPI agents.

Starlette has no built-in body cap, so a handler's `await request.json()`
buffers whatever arrives. These processes run with no memory ceiling of their
own alongside JVMs that are deliberately capped at 224-320MB (see CLAUDE.md on
why those caps are load-bearing), so an oversized body is a cheap way to push
the whole host into swap or an OOM kill.

The limit matters most for the agents that accept free text destined for an LLM
prompt — a large body is both a memory cost here and a token cost at the
provider.

What this catches, and what it does not
---------------------------------------
`Content-Length` is checked before the body is read, which rejects the ordinary
case for free. A chunked request sends no Content-Length, so the body is
instead counted as it streams and the request is failed the moment the running
total crosses the cap — that costs one pass over the bytes but never buffers
more than the limit.

This is a resource guard, not an authorization control. The agents already
require `X-Internal-Token` and bind loopback (C5); anything reaching them is
inside the trust boundary, and this exists so that a bug or a runaway upstream
cannot take the host down.
"""

import logging
from typing import Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

logger = logging.getLogger(__name__)

# 2MB, matching the Java services' Tomcat/codec caps. The largest legitimate
# body an agent receives is a plan plus source context, or a commit diff, and
# the diff is already truncated to ~12k characters before it is sent.
DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024


class BodySizeLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, max_body_bytes: int = DEFAULT_MAX_BODY_BYTES):
        super().__init__(app)
        self.max_body_bytes = max_body_bytes

    async def dispatch(self, request: Request, call_next: Callable):
        declared = request.headers.get("content-length")
        if declared is not None:
            try:
                if int(declared) > self.max_body_bytes:
                    return self._too_large(request, declared)
            except ValueError:
                # A malformed Content-Length is not something to guess about.
                return JSONResponse(
                    status_code=400,
                    content={"detail": "Malformed Content-Length header."},
                )

        # No Content-Length (chunked): count as it streams. receive() is wrapped
        # rather than the body being read here, so a request under the limit is
        # still delivered to the handler normally.
        if declared is None:
            request = _limit_streamed_body(request, self.max_body_bytes)

        try:
            return await call_next(request)
        except _BodyTooLarge:
            return self._too_large(request, "chunked")

    def _too_large(self, request: Request, size: str) -> JSONResponse:
        logger.warning(
            "Rejected oversized request body on %s (declared=%s, limit=%d bytes)",
            request.url.path, size, self.max_body_bytes,
        )
        return JSONResponse(
            status_code=413,
            content={
                "detail": f"Request body exceeds the {self.max_body_bytes} byte limit."
            },
        )


class _BodyTooLarge(Exception):
    """Raised mid-stream once a chunked body crosses the cap."""


def _limit_streamed_body(request: Request, max_bytes: int) -> Request:
    original_receive = request.receive
    seen = 0

    async def counting_receive():
        nonlocal seen
        message = await original_receive()
        if message["type"] == "http.request":
            seen += len(message.get("body", b""))
            if seen > max_bytes:
                raise _BodyTooLarge()
        return message

    return Request(request.scope, counting_receive)
