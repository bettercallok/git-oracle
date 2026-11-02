"""Builds the golden eval repo and dataset.

Layout of the generated repo (pushed to the fixture repo with --push):

    main        base commit: correct code, every test passes
    case/<id>   main → the commit that INTRODUCES case <id>'s bug → an unrelated
                follow-up commit

Each case gets its own branch because the test runner runs the repo's whole
suite. When both bugs sat on one main, no fix for case2 could ever pass: case1's
still-failing test failed the suite. That is exactly how the second CI eval run
escalated a case2 fix whose own change was fine.

The follow-up commit keeps the bug commit from being HEAD. The orchestrator used
to pre-fill rootCommit with HEAD, so a case whose bug was HEAD scored a correct
root cause even when the investigator never answered. Case1's old "bug commit"
also only added a comment; the NPE was there from the first commit, so an
investigator that blamed the first commit was arguably right and scored wrong.
Now the bug commit is the one that actually removes the guard.

Each case directory in golden/ holds bug_description.txt, expected_root_commit.txt,
expected_fix.patch and branch.txt (read by run_evals.py).
"""
import argparse
import difflib
import os
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Tuple

REPO_PATH = "/tmp/gitoracle-eval-repo"
GOLDEN_DIR = Path(__file__).parent / "golden"
PKG = "src/main/java/com/example"
TEST_PKG = "src/test/java/com/example"


def run_git(*args, cwd=REPO_PATH):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


def git_out(*args) -> str:
    return subprocess.check_output(["git", *args], cwd=REPO_PATH).decode().strip()


def write(rel_path: str, content: str):
    path = os.path.join(REPO_PATH, rel_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)


def commit(message: str) -> str:
    run_git("add", ".")
    run_git("commit", "-m", message)
    return git_out("rev-parse", "HEAD")


# ─── Base: correct code with passing tests ───────────────────────────────────

POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>gitoracle-eval-repo</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
"""

USER_SERVICE_OK = """package com.example;

public class UserService {
    private PaymentGateway paymentGateway;

    public UserService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void processPayment(User user, PaymentInfo payment) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        String userId = user.getId();
        paymentGateway.charge(userId, payment.getAmount());
    }
}
"""

BATCH_PROCESSOR_OK = """package com.example;

import java.util.List;

public class BatchProcessor {
    public int process(List<String> items) {
        int processed = 0;
        for (int i = 0; i < items.size(); i++) {
            System.out.println(items.get(i));
            processed++;
        }
        return processed;
    }
}
"""

AUTH_SERVICE_OK = """package com.example;

public class AuthService {
    public boolean isAdmin(String role) {
        return "admin".equals(role);
    }
}
"""

STATS_OK = """package com.example;

public class Stats {
    public double average(int[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        int sum = 0;
        for (int v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }
}
"""

RATE_LIMITER_OK = """package com.example;

public class RateLimiter {
    private final int limit;

    public RateLimiter(int limit) {
        this.limit = limit;
    }

    /** True if one more request is allowed after {@code requestsSoFar}. */
    public boolean allow(int requestsSoFar) {
        return requestsSoFar < limit;
    }
}
"""

HTTP_CLIENT_CONFIG_OK = """package com.example;

public class HttpClientConfig {
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final int timeoutSeconds;

    public HttpClientConfig() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    public HttpClientConfig(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}
"""

BASE_FILES: Dict[str, str] = {
    "pom.xml": POM,
    f"{PKG}/UserService.java": USER_SERVICE_OK,
    f"{PKG}/User.java": """package com.example;

public class User {
    private String id;
    public String getId() { return id; }
}
""",
    f"{PKG}/PaymentInfo.java": """package com.example;

public class PaymentInfo {
    private double amount;
    public double getAmount() { return amount; }
}
""",
    f"{PKG}/PaymentGateway.java": """package com.example;

public class PaymentGateway {
    public void charge(String userId, double amount) {}
}
""",
    f"{PKG}/BatchProcessor.java": BATCH_PROCESSOR_OK,
    # Passes on main, fails once case npe's bug commit removes the guard. Any
    # deliberate guard passes again — it doesn't pin one exception type/message.
    f"{TEST_PKG}/UserServiceTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

public class UserServiceTest {
    @Test
    void processPayment_withNullUser_doesNotThrowRawNPE() {
        UserService service = new UserService(new PaymentGateway());
        PaymentInfo payment = new PaymentInfo();
        try {
            service.processPayment(null, payment);
            // Completing without throwing is also an acceptable, intentional guard.
        } catch (NullPointerException e) {
            fail("processPayment must not let a raw NullPointerException escape for a null user "
                + "— validate explicitly instead");
        } catch (Exception e) {
            // Any other exception (e.g. IllegalArgumentException) is an acceptable, intentional guard.
        }
    }
}
""",
    # Passes on main, fails once case ioob's bug commit changes < to <=.
    f"{TEST_PKG}/BatchProcessorTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BatchProcessorTest {
    @Test
    void process_handlesEveryItemExactlyOnce() {
        assertEquals(3, new BatchProcessor().process(List.of("a", "b", "c")));
    }
}
""",
    f"{PKG}/AuthService.java": AUTH_SERVICE_OK,
    f"{PKG}/Stats.java": STATS_OK,
    f"{PKG}/RateLimiter.java": RATE_LIMITER_OK,
    f"{PKG}/HttpClientConfig.java": HTTP_CLIENT_CONFIG_OK,
    f"{TEST_PKG}/AuthServiceTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthServiceTest {
    @Test
    void isAdmin_comparesTheRoleByValue() {
        // A role read from a request or the database is a distinct String instance.
        assertTrue(new AuthService().isAdmin(new String("admin")));
    }
}
""",
    f"{TEST_PKG}/StatsTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class StatsTest {
    @Test
    void average_keepsTheFraction() {
        assertEquals(1.5, new Stats().average(new int[] {1, 2}), 1e-9);
    }
}
""",
    f"{TEST_PKG}/RateLimiterTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RateLimiterTest {
    @Test
    void allow_admitsExactlyLimitRequests() {
        RateLimiter limiter = new RateLimiter(3);
        assertTrue(limiter.allow(2));
        assertFalse(limiter.allow(3));
    }
}
""",
    f"{TEST_PKG}/HttpClientConfigTest.java": """package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientConfigTest {
    @Test
    void defaultTimeout_isPositive() {
        // 0 means "wait forever" to most HTTP clients: a hung upstream hangs us.
        assertTrue(new HttpClientConfig().timeoutSeconds() > 0);
    }
}
""",
}


# ─── Cases ───────────────────────────────────────────────────────────────────

@dataclass
class Case:
    id: str
    branch: str
    description: str
    bug_message: str
    bug_files: Dict[str, str]          # files the bug commit rewrites (from correct code)
    # Unrelated commits after the bug, so the bug commit is never HEAD.
    followups: List[Tuple[str, Dict[str, str]]] = field(default_factory=list)

    def expected_fix(self) -> str:
        """The golden patch: from the bug commit's version of each file back to
        the correct one. Every bug is introduced into correct code, so undoing it
        is the fix — and generating the diff keeps its line numbers exact (the
        hand-written patches this replaces had to be kept in sync by hand)."""
        out = []
        for rel_path, buggy in self.bug_files.items():
            out.extend(difflib.unified_diff(
                buggy.splitlines(keepends=True), BASE_FILES[rel_path].splitlines(keepends=True),
                fromfile=f"a/{rel_path}", tofile=f"b/{rel_path}"))
        return "".join(out)


def _edit(rel_path: str, old: str, new: str) -> Dict[str, str]:
    """A bug commit that changes one snippet of a correct base file."""
    assert BASE_FILES[rel_path].count(old) == 1, (rel_path, old)
    return {rel_path: BASE_FILES[rel_path].replace(old, new)}


README_FOLLOWUP = ("docs: add README", {"README.md": "# Payments service\n\nInternal payment processing.\n"})
EDITORCONFIG_FOLLOWUP = ("chore: add .editorconfig",
                         {".editorconfig": "root = true\n\n[*.java]\nindent_style = space\nindent_size = 4\n"})
GITIGNORE_FOLLOWUP = ("chore: ignore build output", {".gitignore": "target/\n*.class\n"})

CASES = [
    Case(
        id="case1_npe",
        branch="case/npe",
        description="NullPointerException in UserService.processPayment (user.getId()). "
                    "processPayment is sometimes called with a null user.",
        bug_message="refactor: simplify processPayment",
        bug_files=_edit(f"{PKG}/UserService.java", """        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
""", ""),
        followups=[README_FOLLOWUP],
    ),
    Case(
        id="case2_ioob",
        branch="case/ioob",
        description="IndexOutOfBoundsException in BatchProcessor.process (items.get(i)). "
                    "The loop runs one past the end of the list.",
        bug_message="perf: tighten BatchProcessor loop",
        bug_files=_edit(f"{PKG}/BatchProcessor.java", "i < items.size()", "i <= items.size()"),
        followups=[EDITORCONFIG_FOLLOWUP],
    ),
    Case(
        id="case3_string_eq",
        branch="case/string-eq",
        description="AuthService.isAdmin returns false for real admin users. Roles loaded from the "
                    "database are never recognised as admin, although the value is \"admin\".",
        bug_message="refactor: inline role comparison",
        bug_files=_edit(f"{PKG}/AuthService.java", 'return "admin".equals(role);', 'return role == "admin";'),
        followups=[README_FOLLOWUP],
    ),
    Case(
        id="case4_int_division",
        branch="case/int-division",
        description="Stats.average returns 1.0 for [1, 2] instead of 1.5 — averages lose their "
                    "fractional part.",
        bug_message="refactor: drop redundant cast in Stats.average",
        bug_files=_edit(f"{PKG}/Stats.java", "return (double) sum / values.length;",
                        "return sum / values.length;"),
        followups=[GITIGNORE_FOLLOWUP],
    ),
    Case(
        id="case5_boundary",
        branch="case/boundary",
        description="RateLimiter lets one request too many through: with a limit of 3, a 4th "
                    "request is still allowed.",
        bug_message="fix: off-by-one in RateLimiter",
        bug_files=_edit(f"{PKG}/RateLimiter.java", "return requestsSoFar < limit;",
                        "return requestsSoFar <= limit;"),
        followups=[EDITORCONFIG_FOLLOWUP],
    ),
    Case(
        id="case6_default_timeout",
        branch="case/default-timeout",
        description="Requests made with a default HttpClientConfig hang forever when the upstream "
                    "is slow — the default timeout is 0 (no timeout).",
        bug_message="config: make default timeout configurable",
        bug_files=_edit(f"{PKG}/HttpClientConfig.java", "DEFAULT_TIMEOUT_SECONDS = 30;",
                        "DEFAULT_TIMEOUT_SECONDS = 0;"),
        # Two commits after the bug: the cause is further back than HEAD~1.
        followups=[README_FOLLOWUP, GITIGNORE_FOLLOWUP],
    ),
]


def setup_repo():
    if os.path.exists(REPO_PATH):
        shutil.rmtree(REPO_PATH)
    os.makedirs(REPO_PATH)
    # Pin the branch name: git's default (master vs main) depends on the
    # machine's init.defaultBranch, and --push publishes "main".
    run_git("init", "-b", "main")
    run_git("config", "user.email", "test@gitoracle.ai")
    run_git("config", "user.name", "Test User")

    if os.path.exists(GOLDEN_DIR):
        shutil.rmtree(GOLDEN_DIR)
    os.makedirs(GOLDEN_DIR)

    for rel_path, content in BASE_FILES.items():
        write(rel_path, content)
    commit("Initial commit: payments service with tests")

    for case in CASES:
        run_git("checkout", "-q", "-b", case.branch, "main")
        for rel_path, content in case.bug_files.items():
            write(rel_path, content)
        bug_sha = commit(case.bug_message)
        for message, files in case.followups:
            for rel_path, content in files.items():
                write(rel_path, content)
            commit(message)

        case_dir = GOLDEN_DIR / case.id
        os.makedirs(case_dir)
        (case_dir / "bug_description.txt").write_text(case.description)
        (case_dir / "expected_root_commit.txt").write_text(bug_sha)
        (case_dir / "expected_fix.patch").write_text(case.expected_fix())
        (case_dir / "branch.txt").write_text(case.branch)

    run_git("checkout", "-q", "main")
    print(f"Setup complete. Repo at {REPO_PATH} ({len(CASES)} case branches), "
          f"golden dataset at {GOLDEN_DIR}")


def push_repo(remote_url: str):
    """Force-push main and every case branch to the GitHub fixture repo.

    The pipeline only clones https://github.com/... URLs (RepoRefValidator), so
    a local file:// repo can't be evaluated. Every run rebuilds history from
    scratch, so the push has to be --force; the expected_root_commit SHAs just
    written are the ones being pushed. remote_url may carry a token, so the
    CalledProcessError (whose message repeats the full command line) is never
    allowed to propagate as-is.
    """
    refs = ["main"] + [case.branch for case in CASES]
    try:
        run_git("push", "--force", remote_url, *refs)
    except subprocess.CalledProcessError as e:
        stderr = (e.stderr or b"").decode(errors="replace").replace(remote_url, "<fixture remote>")
        raise SystemExit(f"git push to the fixture remote failed (exit {e.returncode}):\n{stderr}") from None
    print(f"Pushed {', '.join(refs)} to the fixture remote.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build the golden eval repo and dataset")
    parser.add_argument("--push", metavar="REMOTE_URL", default=os.environ.get("EVAL_FIXTURE_PUSH_URL"),
                        help="Force-push the repo here, e.g. https://x-access-token:TOKEN@github.com/owner/repo.git "
                             "(default: $EVAL_FIXTURE_PUSH_URL)")
    args = parser.parse_args()
    setup_repo()
    if args.push:
        push_repo(args.push)
