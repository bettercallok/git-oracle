package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jest command used to be "(npx jest || npm install -g jest && jest)": with
 * equal-precedence || and &&, a passing npx run was followed by a bare `jest`,
 * which is not on PATH, so every green JavaScript suite was reported as failed.
 */
class JestCommandTest {

    private static final String COMMAND = TestFramework.NPM_JEST.getCommand();

    @Test
    void runsJestOnceAndNeverInstallsGlobally() {
        assertThat(COMMAND).doesNotContain("&& jest");
        assertThat(COMMAND).doesNotContain("npm install -g");
        assertThat(COMMAND).contains("npx --yes jest --no-coverage");
    }

    @Test
    void aPassingJestRunExitsZero(@TempDir Path bin) throws Exception {
        assertThat(runWithStubs(bin, 0)).isZero();
    }

    @Test
    void aFailingJestRunExitsNonZero(@TempDir Path bin) throws Exception {
        assertThat(runWithStubs(bin, 1)).isEqualTo(1);
    }

    /** Runs the real command string with stub npm/npx on PATH; npx exits with {@code npxExit}. */
    @Test
    void aProjectWithItsOwnTestScriptIsRunWithIt(@TempDir Path bin) throws Exception {
        // The node stub answers "yes, there is a test script"; npm is then the
        // runner, so its exit code is the result and npx (jest) is never used.
        stub(bin.resolve("node"), 0);
        stub(bin.resolve("npm"), 3);
        stub(bin.resolve("npx"), 0);
        assertThat(run(bin)).isEqualTo(3);
    }

    @Test
    void theScriptCheckReadsPackageJson(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(onPath("node"), "node is not installed");
        String check = COMMAND.substring(COMMAND.indexOf("node -e"), COMMAND.indexOf("; then"));
        Files.writeString(dir.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest run\"}}");
        assertThat(sh(dir, check)).isZero();
        Files.writeString(dir.resolve("package.json"),
            "{\"scripts\":{\"test\":\"echo \\\"Error: no test specified\\\" && exit 1\"}}");
        assertThat(sh(dir, check)).isNotZero();
        Files.writeString(dir.resolve("package.json"), "{}");
        assertThat(sh(dir, check)).isNotZero();
    }

    private static boolean onPath(String tool) {
        for (String dir : System.getenv("PATH").split(":")) {
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    private static int sh(Path dir, String command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-c", command).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private static int runWithStubs(Path bin, int npxExit) throws IOException, InterruptedException {
        stub(bin.resolve("npm"), 0);
        stub(bin.resolve("npx"), npxExit);
        stub(bin.resolve("node"), 1);          // no test script: jest is the runner
        return run(bin);
    }

    private static int run(Path bin) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", COMMAND);
        // Only the stub directory and the basic system tools: no real jest anywhere.
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private static void stub(Path file, int exit) throws IOException {
        Files.writeString(file, "#!/bin/sh\nexit " + exit + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
