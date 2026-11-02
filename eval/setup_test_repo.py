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
import os
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict

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
}


# ─── Cases ───────────────────────────────────────────────────────────────────

@dataclass
class Case:
    id: str
    branch: str
    description: str
    bug_message: str
    bug_files: Dict[str, str]          # files rewritten by the bug commit
    expected_fix: str                  # golden patch, against the bug commit
    followup_message: str
    followup_files: Dict[str, str] = field(default_factory=dict)


CASES = [
    Case(
        id="case1_npe",
        branch="case/npe",
        description="NullPointerException in UserService.processPayment (user.getId()). "
                    "processPayment is sometimes called with a null user.",
        bug_message="refactor: simplify processPayment",
        bug_files={f"{PKG}/UserService.java": """package com.example;

public class UserService {
    private PaymentGateway paymentGateway;

    public UserService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void processPayment(User user, PaymentInfo payment) {
        String userId = user.getId();
        paymentGateway.charge(userId, payment.getAmount());
    }
}
"""},
        expected_fix="""--- a/src/main/java/com/example/UserService.java
+++ b/src/main/java/com/example/UserService.java
@@ -8,6 +8,9 @@
     }

     public void processPayment(User user, PaymentInfo payment) {
+        if (user == null) {
+            throw new IllegalArgumentException("User cannot be null");
+        }
         String userId = user.getId();
         paymentGateway.charge(userId, payment.getAmount());
     }
""",
        followup_message="docs: add README",
        followup_files={"README.md": "# Payments service\n\nInternal payment processing.\n"},
    ),
    Case(
        id="case2_ioob",
        branch="case/ioob",
        description="IndexOutOfBoundsException in BatchProcessor.process (items.get(i)). "
                    "The loop runs one past the end of the list.",
        bug_message="perf: tighten BatchProcessor loop",
        bug_files={f"{PKG}/BatchProcessor.java": BATCH_PROCESSOR_OK.replace(
            "i < items.size()", "i <= items.size()")},
        expected_fix="""--- a/src/main/java/com/example/BatchProcessor.java
+++ b/src/main/java/com/example/BatchProcessor.java
@@ -5,7 +5,7 @@
 public class BatchProcessor {
     public int process(List<String> items) {
         int processed = 0;
-        for (int i = 0; i <= items.size(); i++) {
+        for (int i = 0; i < items.size(); i++) {
             System.out.println(items.get(i));
             processed++;
         }
""",
        followup_message="chore: add .editorconfig",
        followup_files={".editorconfig": "root = true\n\n[*.java]\nindent_style = space\nindent_size = 4\n"},
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
        for rel_path, content in case.followup_files.items():
            write(rel_path, content)
        commit(case.followup_message)

        case_dir = GOLDEN_DIR / case.id
        os.makedirs(case_dir)
        (case_dir / "bug_description.txt").write_text(case.description)
        (case_dir / "expected_root_commit.txt").write_text(bug_sha)
        (case_dir / "expected_fix.patch").write_text(case.expected_fix)
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
