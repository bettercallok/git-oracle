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
from shared.health import add_health_route
from shared.body_limit import BodySizeLimitMiddleware

app = FastAPI(title="GitOracle Guardrails Engine", dependencies=[Depends(require_internal_token)])
app.add_middleware(BodySizeLimitMiddleware)
add_health_route(app, "guardrails")
# No CORS middleware: agents are called server-to-server only (orchestrator,
# other agents), never from a browser. allow_origins=["*"] with
# allow_credentials=True let any web page a developer visited call them.

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
    from shared.config import check_startup_config
    check_startup_config("guardrails", needs_llm=False)
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=9006)
