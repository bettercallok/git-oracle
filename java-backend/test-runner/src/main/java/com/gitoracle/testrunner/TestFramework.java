package com.gitoracle.testrunner;

public enum TestFramework {
    MAVEN("mvn test -q"),
    GRADLE("./gradlew test --quiet"),
    PYTEST("python -m pytest -v --tb=short --json-report"),
    NPM_JEST("npm test -- --json"),
    CARGO("cargo test 2>&1"),
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
