package com.gitoracle.testrunner;

public enum TestFramework {
    MAVEN("mvn test -q --no-transfer-progress"),
    GRADLE("./gradlew test --no-daemon --quiet"),
    PYTEST("pip install -q pytest 2>/dev/null && (pip install -q -r requirements.txt 2>/dev/null || true) && python -m pytest -v --tb=short"),
    NPM_JEST("(npm ci --silent 2>/dev/null || true) && (npx jest --no-coverage || npm install -g jest && jest --no-coverage)"),
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
