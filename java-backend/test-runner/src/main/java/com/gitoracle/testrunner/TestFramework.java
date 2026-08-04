package com.gitoracle.testrunner;

public enum TestFramework {
    MAVEN("mvn test -q --no-transfer-progress"),
    GRADLE("./gradlew test --no-daemon --quiet"),
    PYTEST("python -m pytest -v --tb=short"),
    NPM_JEST("npx jest --no-coverage"),
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
