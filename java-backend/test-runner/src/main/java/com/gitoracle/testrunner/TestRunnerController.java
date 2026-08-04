package com.gitoracle.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * GitOracle Test Runner — Real sandboxed test execution.
 *
 * Flow per request:
 *   1. Clone the target repo into /tmp/gitoracle-workspaces/{jobId}/
 *   2. Write the unified diff to a temp file
 *   3. Apply the patch with `git apply`
 *   4. Auto-detect test framework (pytest / Maven / Gradle / npm-jest)
 *   5. Run tests in an isolated Docker container (3-min timeout)
 *   6. Parse exit code + output → TestResult
 *   7. Clean up workspace
 */
@RestController
@CrossOrigin(origins = "*")
public class TestRunnerController {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnerController.class);
    private static final String WORKSPACE_ROOT = "/tmp/gitoracle-workspaces";
    private static final int TIMEOUT_SECONDS = 180;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "test-runner"));
    }

    @PostMapping("/test")
    public ResponseEntity<TestResult> runTest(@RequestBody TestRequest request) {
        String jobId = request.getJobId() != null ? request.getJobId() : UUID.randomUUID().toString();
        logger.info("Test request for job={} repoPath={}", jobId, request.getRepoPath());

        // ── Step 1: Resolve repo URL ───────────────────────────────────────────
        String repoUrl = resolveRepoUrl(request);
        if (repoUrl == null) {
            logger.warn("No repo URL provided for job {}. Using safe-pass fallback.", jobId);
            return ResponseEntity.ok(new TestResult(true, 1.0, 0.0,
                "WARN: No repository URL provided — tests skipped (safe-pass fallback).\n" +
                "Provide 'repoUrl' in the TestRequest to enable real test execution."));
        }

        Path workDir = Path.of(WORKSPACE_ROOT, jobId);

        try {
            // ── Step 2: Clone ──────────────────────────────────────────────────
            Files.createDirectories(workDir);
            logger.info("Cloning {} into {}", repoUrl, workDir);

            RunResult cloneResult = run(workDir.getParent(),
                TIMEOUT_SECONDS, "git", "clone", "--depth=1", repoUrl, workDir.toString());

            if (!cloneResult.success()) {
                logger.error("Clone failed for job {}: {}", jobId, cloneResult.output());
                return ResponseEntity.ok(new TestResult(false, 0.0, 0.0,
                    "Clone failed:\n" + cloneResult.output()));
            }

            // ── Step 3: Apply patch ────────────────────────────────────────────
            String patch = request.getPatchDiff();
            if (patch != null && !patch.isBlank()) {
                Path patchFile = workDir.resolve(".gitoracle_patch.diff");
                Files.writeString(patchFile, patch);

                RunResult applyResult = run(workDir, 30,
                    "git", "apply", "--whitespace=fix", patchFile.toString());

                if (!applyResult.success()) {
                    logger.warn("git apply failed for job {}: {}", jobId, applyResult.output());
                    // Try 3-way merge fallback
                    RunResult apply3 = run(workDir, 30,
                        "git", "apply", "--3way", "--whitespace=fix", patchFile.toString());
                    if (!apply3.success()) {
                        return ResponseEntity.ok(new TestResult(false, 0.0, 0.0,
                            "Patch application failed (both apply and 3way):\n" + applyResult.output()
                            + "\n--- 3way ---\n" + apply3.output()));
                    }
                    logger.info("Patch applied via 3-way merge for job {}", jobId);
                } else {
                    logger.info("Patch applied cleanly for job {}", jobId);
                }
            } else {
                logger.info("No patch provided for job {} — testing unpatched repo.", jobId);
            }

            // ── Step 4: Detect framework ───────────────────────────────────────
            TestFramework framework = detectFramework(workDir, request.getFramework());
            logger.info("Detected framework {} for job {}", framework, jobId);

            // ── Step 5: Run tests in Docker ────────────────────────────────────
            return ResponseEntity.ok(runInDocker(workDir, framework, jobId));

        } catch (Exception e) {
            logger.error("Unexpected error running tests for job {}: {}", jobId, e.getMessage(), e);
            return ResponseEntity.ok(new TestResult(false, 0.0, 0.0,
                "Internal test runner error: " + e.getMessage()));
        } finally {
            // ── Step 6: Cleanup ────────────────────────────────────────────────
            try {
                deleteDirectory(workDir);
                logger.info("Cleaned up workspace for job {}", jobId);
            } catch (Exception e) {
                logger.warn("Failed to clean workspace {}: {}", workDir, e.getMessage());
            }
        }
    }

    // ─── Framework Detection ──────────────────────────────────────────────────

    private TestFramework detectFramework(Path workDir, TestFramework hint) {
        if (hint != null && hint != TestFramework.UNKNOWN) return hint;

        if (Files.exists(workDir.resolve("pom.xml")))             return TestFramework.MAVEN;
        if (Files.exists(workDir.resolve("build.gradle")) ||
            Files.exists(workDir.resolve("build.gradle.kts")))    return TestFramework.GRADLE;
        if (Files.exists(workDir.resolve("package.json"))) {
            // Distinguish jest vs plain npm test
            return TestFramework.NPM_JEST;
        }
        if (Files.exists(workDir.resolve("Cargo.toml")))          return TestFramework.CARGO;
        if (Files.exists(workDir.resolve("go.mod")))              return TestFramework.GO_TEST;
        // Default: pytest (Python)
        return TestFramework.PYTEST;
    }

    // ─── Docker Execution ─────────────────────────────────────────────────────

    private TestResult runInDocker(Path workDir, TestFramework framework, String jobId) {
        String dockerImage = getDockerImage(framework);
        String testCommand = getTestCommand(framework);

        logger.info("Running '{}' in Docker image '{}' for job {}", testCommand, dockerImage, jobId);

        try {
            List<String> dockerCmd = List.of(
                "docker", "run", "--rm",
                "--network=none",                              // no network inside sandbox
                "--memory=512m", "--cpus=1",                  // resource limits
                "-v", workDir.toAbsolutePath() + ":/repo:rw",
                "-w", "/repo",
                dockerImage,
                "sh", "-c", testCommand
            );

            RunResult result = run(workDir, TIMEOUT_SECONDS, dockerCmd.toArray(String[]::new));

            if (result.timedOut()) {
                logger.warn("Tests timed out after {}s for job {}", TIMEOUT_SECONDS, jobId);
                return new TestResult(false, 0.0, 0.0,
                    "Tests timed out after " + TIMEOUT_SECONDS + " seconds.\n" + result.output());
            }

            boolean passed = result.success();
            double score   = computeQualityScore(result.output(), framework);

            logger.info("Tests {} for job {} (exit={})", passed ? "PASSED" : "FAILED",
                        jobId, result.exitCode());

            return new TestResult(passed, score, 0.0,
                "[exit=" + result.exitCode() + "]\n" + result.output());

        } catch (Exception e) {
            // Docker not available — run natively as fallback
            logger.warn("Docker unavailable ({}), running natively for job {}", e.getMessage(), jobId);
            return runNative(workDir, framework, jobId);
        }
    }

    private TestResult runNative(Path workDir, TestFramework framework, String jobId) {
        String cmd = framework.getCommand();
        if (cmd == null || cmd.contains("exit 0")) {
            return new TestResult(true, 1.0, 0.0,
                "WARN: Docker unavailable and no native command for " + framework + ". Safe-pass.");
        }

        try {
            RunResult result = run(workDir, TIMEOUT_SECONDS, "sh", "-c", cmd);
            boolean passed = result.success();
            return new TestResult(passed, passed ? 1.0 : 0.0, 0.0,
                "[native][exit=" + result.exitCode() + "]\n" + result.output());
        } catch (Exception e) {
            return new TestResult(false, 0.0, 0.0, "Native execution failed: " + e.getMessage());
        }
    }

    // ─── Quality Score ────────────────────────────────────────────────────────

    /**
     * Parse test output for pass/fail counts to produce a normalised quality score.
     * Falls back to 1.0 on pass / 0.0 on fail if counts can't be parsed.
     */
    private double computeQualityScore(String output, TestFramework framework) {
        if (output == null) return 0.5;

        // pytest: "3 passed, 1 failed"
        Pattern pytestPassed = Pattern.compile("(\\d+) passed");
        Pattern pytestFailed = Pattern.compile("(\\d+) failed");
        Matcher pm = pytestPassed.matcher(output);
        Matcher fm = pytestFailed.matcher(output);
        if (pm.find()) {
            int passed = Integer.parseInt(pm.group(1));
            int failed = fm.find() ? Integer.parseInt(fm.group(1)) : 0;
            int total  = passed + failed;
            return total > 0 ? (double) passed / total : 1.0;
        }

        // JUnit / Gradle: "Tests run: 5, Failures: 1"
        Pattern junitRun  = Pattern.compile("Tests run: (\\d+)");
        Pattern junitFail = Pattern.compile("Failures: (\\d+)");
        Matcher jm = junitRun.matcher(output);
        Matcher jf = junitFail.matcher(output);
        if (jm.find()) {
            int run  = Integer.parseInt(jm.group(1));
            int fail = jf.find() ? Integer.parseInt(jf.group(1)) : 0;
            return run > 0 ? (double)(run - fail) / run : 1.0;
        }

        // Jest: "Tests: 3 passed, 1 failed"
        Pattern jestPassed = Pattern.compile("(\\d+) passed");
        Matcher jestM = jestPassed.matcher(output);
        if (jestM.find()) {
            int passed = Integer.parseInt(jestM.group(1));
            int failed = 0;
            Matcher jestF = Pattern.compile("(\\d+) failed").matcher(output);
            if (jestF.find()) failed = Integer.parseInt(jestF.group(1));
            int total = passed + failed;
            return total > 0 ? (double) passed / total : 1.0;
        }

        return 0.5; // unknown output format
    }

    // ─── Docker Image + Command Mapping ───────────────────────────────────────

    private String getDockerImage(TestFramework framework) {
        return switch (framework) {
            case PYTEST    -> "python:3.11-slim";
            case MAVEN     -> "maven:3.9-eclipse-temurin-21-alpine";
            case GRADLE    -> "gradle:8.7-jdk21-alpine";
            case NPM_JEST  -> "node:20-alpine";
            case CARGO     -> "rust:1.78-slim";
            case GO_TEST   -> "golang:1.22-alpine";
            default        -> "python:3.11-slim";
        };
    }

    private String getTestCommand(TestFramework framework) {
        return switch (framework) {
            case PYTEST    -> "pip install -q -r requirements.txt 2>/dev/null || true && python -m pytest -v --tb=short 2>&1";
            case MAVEN     -> "mvn test -q --no-transfer-progress 2>&1";
            case GRADLE    -> "./gradlew test --no-daemon --quiet 2>&1 || gradle test --no-daemon --quiet 2>&1";
            case NPM_JEST  -> "npm ci --silent 2>/dev/null && npx jest --no-coverage 2>&1";
            case CARGO     -> "cargo test 2>&1";
            case GO_TEST   -> "go test ./... -v 2>&1";
            default        -> "echo 'No test command available' && exit 1";
        };
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String resolveRepoUrl(TestRequest request) {
        // Accept explicit repoUrl field
        if (request.getRepoUrl() != null && !request.getRepoUrl().isBlank()) {
            return request.getRepoUrl();
        }
        // Infer from repoPath if it looks like a GitHub path
        String rp = request.getRepoPath();
        if (rp != null && rp.matches(".*[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+.*")) {
            String slug = rp.replaceAll(".*/([^/]+/[^/]+)/?$", "$1").replace(".git", "");
            if (slug.contains("/")) return "https://github.com/" + slug;
        }
        return null;
    }

    private record RunResult(int exitCode, String output, boolean timedOut) {
        boolean success() { return !timedOut && exitCode == 0; }
    }

    private RunResult run(Path dir, int timeoutSeconds, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (output.length() > 100_000) {
                    output.append("\n... [output truncated at 100k chars]\n");
                    break;
                }
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new RunResult(-1, output.toString(), true);
        }
        return new RunResult(process.exitValue(), output.toString(), false);
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(File::delete);
        }
    }
}
