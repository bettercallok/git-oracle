from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
from injection_detector import detect_injection
from patch_scanner import scan_patch, PatchScanResult

app = FastAPI(title="GitOracle Guardrails Engine")

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
    if not result.safe:
        # We can either return 400 or just return the result with safe=False
        raise HTTPException(status_code=400, detail={"violations": result.violations})
    return result

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=9006)
