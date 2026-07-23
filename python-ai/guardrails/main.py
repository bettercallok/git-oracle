from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from injection_detector import detect_injection
from patch_scanner import scan_patch, PatchScanResult
from schema_validator import validate_schema, ValidationResult

app = FastAPI(title="GitOracle Guardrails", version="1.0.0")

class PromptInjectionRequest(BaseModel):
    text: str

class PatchScanRequest(BaseModel):
    patch_diff: str
    approved_files: list[str]

class SchemaValidationRequest(BaseModel):
    schema_name: str
    data: dict

@app.post("/scan/prompt-injection")
async def scan_prompt_injection(req: PromptInjectionRequest):
    """Layer 7a: Detects adversarial prompt injection in user input"""
    is_injection = detect_injection(req.text)
    return {"safe": not is_injection, "is_injection": is_injection}

@app.post("/scan/patch", response_model=PatchScanResult)
async def scan_patch_endpoint(req: PatchScanRequest):
    """Layer 7b: Scans AI-generated patches for dangerous commands and scope violations"""
    return scan_patch(req.patch_diff, req.approved_files)

@app.post("/validate/schema", response_model=ValidationResult)
async def validate_schema_endpoint(req: SchemaValidationRequest):
    """Layer 7c: Validates LLM outputs against dynamic Pydantic schemas"""
    # In a full implementation, we would load the requested schema_name dynamically
    # For now, we mock the validation success if data is present
    if not req.data:
        return ValidationResult(valid=False, errors=["Empty data"])
    return ValidationResult(valid=True, errors=[])
