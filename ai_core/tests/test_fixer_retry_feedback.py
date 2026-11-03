"""
A fixer retry must be told which test failed and why.

Eval run #10, case1 (job acce7f8e): the plan suggested Objects.requireNonNull,
which still throws a NullPointerException. The test said exactly that, but the
retry prompt only said "your patch failed the tests", so the model regenerated
the identical patch twice and the job escalated with no reason given. These pin
that the failure output reaches the next attempt, a repeated patch is told what
already failed, and an escalation carries the last failure.

MAVEN_LOG is a trimmed copy of that run's real test-runner output.
"""
import asyncio
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import agents.fixer.main as fixer  # noqa: E402

MAVEN_LOG = """[exit=1]
Unable to find image 'maven@sha256:65353f52' locally
55afa1ecc21d: Pulling fs layer
55afa1ecc21d: Download complete
mkdir: cannot create directory '/root': Permission denied
Can not write to /root/.m2/copy_reference_file.log. Wrong volume permissions? Carrying on ...
[INFO] Scanning for projects...
[INFO] --- surefire:3.2.5:test (default-test) @ gitoracle-eval-repo ---
[INFO] Running com.example.AuthServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.095 s -- in com.example.AuthServiceTest
[INFO] Running com.example.UserServiceTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.014 s <<< FAILURE! -- in com.example.UserServiceTest
[ERROR] com.example.UserServiceTest.processPayment_withNullUser_doesNotThrowRawNPE -- Time elapsed: 0.008 s <<< FAILURE!
org.opentest4j.AssertionFailedError: processPayment must not let a raw NullPointerException escape for a null user — validate explicitly instead
\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)
\tat com.example.UserServiceTest.processPayment_withNullUser_doesNotThrowRawNPE(UserServiceTest.java:15)

[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project gitoracle-eval-repo: There are test failures.
[ERROR]
[ERROR] Please refer to /repo/target/surefire-reports for the individual test results.
[ERROR] -> [Help 1]
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
"""
ASSERTION = "processPayment must not let a raw NullPointerException escape for a null user"


# ── summarize_test_failure ──────────────────────────────────────────────────

def test_the_summary_keeps_the_failing_test_and_its_message():
    summary = fixer.summarize_test_failure(MAVEN_LOG)

    assert ASSERTION in summary
    assert "UserServiceTest.processPayment_withNullUser_doesNotThrowRawNPE" in summary
    assert "Tests run: 6, Failures: 1" in summary


def test_the_summary_drops_noise():
    summary = fixer.summarize_test_failure(MAVEN_LOG)

    for noise in ("Pulling fs layer", "Permission denied", "AuthServiceTest", "Please refer to",
                  "[Help 1]", "AssertionUtils.java", "Failed to execute goal"):
        assert noise not in summary, noise


def test_the_summary_works_for_pytest_output_too():
    log = (
        "============ test session starts ============\n"
        "collected 3 items\n"
        "tests/test_calc.py ..F\n"
        "E       assert 0 == 1\n"
        "FAILED tests/test_calc.py::test_divide - assert 0 == 1\n"
        "======= 1 failed, 2 passed in 0.03s =======\n"
    )
    summary = fixer.summarize_test_failure(log)

    assert "FAILED tests/test_calc.py::test_divide" in summary
    assert "E       assert 0 == 1".strip() in summary
    assert "collected 3 items" not in summary


def test_an_unrecognised_failure_falls_back_to_the_end_of_the_log():
    log = "step one\nstep two\nprocess killed by signal 9\n"

    assert fixer.summarize_test_failure(log).endswith("process killed by signal 9")
    assert fixer.summarize_test_failure("") == ""


def test_the_summary_is_bounded():
    log = "\n".join(f"[ERROR] failure number {i} with a fairly long explanation" for i in range(500))

    assert len(fixer.summarize_test_failure(log, max_chars=300)) <= 300


# ── The retry loop ──────────────────────────────────────────────────────────

USER_SERVICE = "src/main/java/com/example/UserService.java"
SOURCE = (
    "package com.example;\n\n"
    "public class UserService {\n"
    "    public String processPayment(User user) {\n"
    "        String userId = user.getId();\n"
    "        return userId;\n"
    "    }\n"
    "}\n"
)
SEARCH = "        String userId = user.getId();\n"
REQUIRE_NON_NULL = fixer.SearchReplaceEdit(
    file_path=USER_SERVICE, search=SEARCH,
    replace='        java.util.Objects.requireNonNull(user, "User cannot be null");\n' + SEARCH,
    summary="Null-check the user with requireNonNull", confidence=0.9)
EXPLICIT_CHECK = fixer.SearchReplaceEdit(
    file_path=USER_SERVICE, search=SEARCH,
    replace=('        if (user == null) {\n'
             '            throw new IllegalArgumentException("User cannot be null");\n'
             '        }\n' + SEARCH),
    summary="Reject a null user explicitly", confidence=0.9)


class _NoMemory:
    async def recall(self, *args, **kwargs):
        return []

    async def remember(self, *args, **kwargs):
        return None


@pytest.fixture
def run_fixer(monkeypatch, tmp_path):
    """Runs execute_fix with the LLM returning `edits` in order and the test runner
    returning `results` in order; returns (result, prompts sent, test calls)."""
    repo = tmp_path / "repo"
    (repo / os.path.dirname(USER_SERVICE)).mkdir(parents=True)
    (repo / USER_SERVICE).write_text(SOURCE)

    async def noop(*args, **kwargs):
        return None

    async def prompt(agent, default):
        return default, "test-version"

    monkeypatch.setattr(fixer, "AgentMemory", _NoMemory)
    monkeypatch.setattr(fixer, "report_event", noop)
    monkeypatch.setattr(fixer, "report_prompt_version", noop)
    monkeypatch.setattr(fixer, "fetch_prompt_versioned", prompt)

    def run(edits, results):
        prompts, test_calls = [], []
        edits, results = list(edits), list(results)

        async def llm(messages, **kwargs):
            prompts.append(messages[-1]["content"])
            return edits.pop(0)

        async def tests(job_id, repo_path, repo_url, patch_diff, branch=""):
            test_calls.append(patch_diff)
            return results.pop(0)

        monkeypatch.setattr(fixer, "llm_structured", llm)
        monkeypatch.setattr(fixer, "run_real_test", tests)
        request = fixer.FixerRequest(
            tenant_id="00000000-0000-0000-0000-000000000000", repo_path=str(repo), job_id="job-1",
            bug_description="NullPointerException in UserService.processPayment",
            plan=fixer.PlannerOutput(
                strategy="surgical_patch", affected_files=[USER_SERVICE],
                affected_functions=["processPayment"], max_lines_to_change=2,
                reasoning="Re-introduce a null validation, e.g. Objects.requireNonNull.", confidence=0.97))
        return asyncio.run(fixer.execute_fix(request)), prompts, test_calls

    return run


def test_a_retry_is_shown_why_the_previous_patch_failed(run_fixer):
    result, prompts, test_calls = run_fixer(
        [REQUIRE_NON_NULL, EXPLICIT_CHECK], [(False, MAVEN_LOG), (True, "")])

    tried = 'requireNonNull(user, "User cannot be null")'      # the plan only says "Objects.requireNonNull"
    assert result.success is True and result.attempts == 2
    assert ASSERTION not in prompts[0] and tried not in prompts[0]
    retry = prompts[1]
    assert ASSERTION in retry                                   # why it failed
    assert tried in retry                                      # what was tried
    assert "source of truth" in retry                          # tests outrank the plan
    assert "Pulling fs layer" not in retry                     # not the whole log
    assert len(test_calls) == 2


def test_a_repeated_patch_is_told_it_already_failed_and_why(run_fixer):
    # Exactly case1: the same patch again on attempt 2, then a different one.
    result, prompts, test_calls = run_fixer(
        [REQUIRE_NON_NULL, REQUIRE_NON_NULL, EXPLICIT_CHECK], [(False, MAVEN_LOG), (True, "")])

    assert result.success is True and result.attempts == 3
    assert len(test_calls) == 2                                # the repeat isn't re-tested
    assert "exactly the same edit" in prompts[2]
    assert ASSERTION in prompts[2]


def test_an_escalation_says_which_test_failed(run_fixer):
    result, _, _ = run_fixer([REQUIRE_NON_NULL] * 3, [(False, MAVEN_LOG)])

    assert result.success is False
    assert result.escalation_report.startswith("Fixer Agent exhausted all attempts")
    assert ASSERTION in result.escalation_report


def test_an_escalation_without_any_test_run_keeps_the_plain_reason(run_fixer):
    # All three edits rejected before testing (search text not in the file).
    bad = fixer.SearchReplaceEdit(file_path=USER_SERVICE, search="no such line\n", replace="x\n",
                                  summary="bad", confidence=0.1)
    result, _, test_calls = run_fixer([bad] * 3, [])

    assert test_calls == []
    assert result.escalation_report == "Fixer Agent exhausted all attempts and could not fix the bug."
