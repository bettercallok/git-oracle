import re

INJECTION_PATTERNS = [
    r"ignore previous instructions",
    r"you are now",
    r"system:\s",
    r"<\|im_start\|>",
    r"forget everything",
    r"new persona",
]

def detect_injection(text: str) -> bool:
    """Returns True if a prompt injection pattern is detected."""
    text_lower = text.lower()
    return any(re.search(p, text_lower) for p in INJECTION_PATTERNS)
