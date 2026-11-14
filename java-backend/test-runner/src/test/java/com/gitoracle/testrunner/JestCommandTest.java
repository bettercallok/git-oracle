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
    private static int runWithStubs(Path bin, int npxExit) throws IOException, InterruptedException {
        stub(bin.resolve("npm"), 0);
        stub(bin.resolve("npx"), npxExit);
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
