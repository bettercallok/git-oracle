"""Limits two agents must agree on.

Guardrails rejects a patch that changes more than PATCH_LINE_LIMIT lines, while
plans asked the fixer for "up to 200" or "up to 300". The fixer never knew the
real limit, so a larger patch passed its own tests and was then failed by
guardrails after all the work. Both read the number from here.
"""

PATCH_LINE_LIMIT = 50


def count_changed_lines(diff: str) -> int:
    """Added plus removed lines in a unified diff, not counting file headers."""
    changes = 0
    for line in (diff or "").split("\n"):
        if (line.startswith("+") and not line.startswith("+++")) or \
           (line.startswith("-") and not line.startswith("---")):
            changes += 1
    return changes
