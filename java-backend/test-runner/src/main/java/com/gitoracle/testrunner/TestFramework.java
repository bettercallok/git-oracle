package com.gitoracle.testrunner;

public enum TestFramework {
    // Not -q: quiet mode prints Surefire's "Tests run: N, Failures: F, ..." summary
    // only when something fails, so a green run had no counts and its quality
    // score fell back to an "unknown format" 0.5 — shown on PRs as "Fix Quality: 50%".
    MAVEN("mvn test --batch-mode --no-transfer-progress"),
    GRADLE("./gradlew test --no-daemon --quiet"),
    PYTEST("pip install -q pytest 2>/dev/null && (pip install -q -r requirements.txt 2>/dev/null || true) && python -m pytest -v --tb=short"),
    // One jest invocation. The old "(npx jest || npm install -g jest && jest)" ran a
    // bare `jest` after a PASSING npx run (|| and && have equal precedence), and
    // bare jest is not on PATH, so green suites exited 127. A global install could
    // never have worked anyway: the sandbox root filesystem is read-only.
    NPM_JEST("(npm ci --silent 2>/dev/null || true) && npx --yes jest --no-coverage"),
    CARGO("cargo test"),
    GO_TEST("go test ./... -v"),
    UNKNOWN(null);

    private final String command;

    TestFramework(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }
}
