import os
import re
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="GitOracle Guardrails Service")

# --- Models ---

class ScanRequest(BaseModel):
    content: str
    context: Optional[str] = None

class ScanIssue(BaseModel):
    severity: str  # "HIGH", "MEDIUM", "LOW"
    rule_id: str
    description: str

class ScanResponse(BaseModel):
    is_safe: bool
    issues: List[ScanIssue]

# --- Simple Rule Sets ---

# Very basic heuristic rules for demonstration
FORBIDDEN_PATTERNS = [
    (r"os\.system\(.*user_input", "OS Command Injection risk"),
    (r"exec\(", "Execution of dynamic code is highly restricted"),
    (r"eval\(", "Evaluation of dynamic code is highly restricted"),
    (r"password\s*=\s*['\"][^'\"]+['\"]", "Hardcoded credential detected"),
    (r"secret\s*=\s*['\"][^'\"]+['\"]", "Hardcoded secret detected"),
    (r"API_KEY\s*=\s*['\"][^'\"]+['\"]", "Hardcoded API key detected"),
]

PROMPT_INJECTION_PATTERNS = [
    (r"(?i)ignore previous instructions", "Potential prompt injection: ignore instructions"),
    (r"(?i)system prompt", "Potential prompt injection: system prompt exposure"),
    (r"(?i)you are now", "Potential prompt injection: role override"),
    (r"(?i)output your instructions", "Potential prompt injection: instruction exfiltration"),
]

# --- Endpoints ---

@app.get("/health")
async def health_check():
    return {"status": "ok"}

@app.post("/scan/patch", response_model=ScanResponse)
async def scan_patch(request: ScanRequest):
    """
    Scans a generated code patch for obvious security flaws, hardcoded secrets,
    and potential malicious logic inserted by the LLM.
    """
    issues = []
    content = request.content
    
    for pattern, description in FORBIDDEN_PATTERNS:
        if re.search(pattern, content):
            issues.append(ScanIssue(
                severity="HIGH",
                rule_id="FORBIDDEN_PATTERN",
                description=description
            ))
            
    # Also check if it's too large (scope creep check)
    if len(content) > 50000:
        issues.append(ScanIssue(
            severity="MEDIUM",
            rule_id="EXCESSIVE_SIZE",
            description="Patch is unusually large, could indicate scope creep or hallucination."
        ))

    return ScanResponse(
        is_safe=len(issues) == 0,
        issues=issues
    )

@app.post("/scan/prompt", response_model=ScanResponse)
async def scan_prompt(request: ScanRequest):
    """
    Scans user-provided input (e.g., issue description or PR comments)
    for prompt injection attacks before injecting them into the LLM context.
    """
    issues = []
    content = request.content
    
    for pattern, description in PROMPT_INJECTION_PATTERNS:
        if re.search(pattern, content):
            issues.append(ScanIssue(
                severity="HIGH",
                rule_id="PROMPT_INJECTION",
                description=description
            ))

    return ScanResponse(
        is_safe=len(issues) == 0,
        issues=issues
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", 9006)))
