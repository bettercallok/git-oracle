import logging
import os
import sys
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
from injection_detector import detect_injection
from patch_scanner import scan_patch, PatchScanResult

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.internal_auth import require_internal_token

app = FastAPI(title="GitOracle Guardrails Engine", dependencies=[Depends(require_internal_token)])
# No CORSMiddleware.
#
# This agent used to run allow_origins=["*"] together with
# allow_credentials=True. That pairing is rejected by every browser (the spec
# forbids a wildcard origin on a credentialed request), so it never did what it
# looked like it did — but it advertised an intent to be called cross-origin by
# a browser, which is exactly what this service must not be.
#
# Nothing browser-side calls the agents. The dashboard talks only to the API
# gateway on :8080, which owns CORS centrally (and has a test pinning that it is
# the only place setting the header — a duplicate broke CORS outright once).
# Since C5 these agents bind 127.0.0.1 and require X-Internal-Token, so their
# callers are other services, and service-to-service calls have no origin and
# need no CORS at all.
#
# Removing it is not cosmetic: CORS headers on a service that should never be
# reached by a browser turn a future SSRF or a misrouted proxy into something a
# page can read the response of, instead of something the browser blocks.

class TextPayload(BaseModel):
    text: str

class PatchPayload(BaseModel):
    diff: str
    allowed_files: list[str]

@app.post("/validate/injection")
async def validate_injection(payload: TextPayload):
    is_injected = detect_injection(payload.text)
    if is_injected:
        raise HTTPException(status_code=400, detail="Prompt injection detected")
    return {"status": "safe"}

@app.post("/validate/patch", response_model=PatchScanResult)
async def validate_patch(payload: PatchPayload):
    result = scan_patch(payload.diff, payload.allowed_files)

    # Advisories are reported on BOTH paths, and never decide the outcome.
    # They are pattern matches over the patch's added lines, useful for drawing
    # a reviewer's eye and useless as a boundary — see CONTENT_HEURISTICS in
    # patch_scanner for why treating them as a gate was false assurance in both
    # directions.
    if result.advisories:
        logging.warning(
            "Patch advisories (non-blocking) for files %s: %s",
            result.touched_files, result.advisories,
        )

    if not result.safe:
        raise HTTPException(
            status_code=400,
            detail={
                "violations": result.violations,
                "advisories": result.advisories,
                "touched_files": result.touched_files,
            },
        )
    return result

if __name__ == "__main__":
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=9006)
