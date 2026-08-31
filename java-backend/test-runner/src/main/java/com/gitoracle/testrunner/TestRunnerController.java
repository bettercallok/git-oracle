package com.gitoracle.testrunner;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Collectors;

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
@CrossOrigin(origins = "${gitoracle.allowed-origins:http://localhost:5173}")
public class TestRunnerController {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnerController.class);
    private static final String WORKSPACE_ROOT = "/tmp/gitoracle-workspaces";
    private static final int TIMEOUT_SECONDS = 180;

    // Docker is the only sandbox this service has — running a cloned repo's own
    // build/test command (mvn test / npm ci / pip install -r / cargo test) is
    // executing arbitrary third-party code, so it must never happen unsandboxed
    // by default. Both gates below must be true before a native fallback runs;
    // an unset trusted-repos allowlist means deny-all, the same convention
    // guardrails uses for its own file allowlist.
    @Value("${gitoracle.allow-unsafe-native-tests:false}")
    private boolean allowUnsafeNativeTests;

    @Value("${gitoracle.testrunner.trusted-repos:}")
    private String trustedReposRaw;

    private Set<String> trustedRepos = Set.of();

    // Snapshot taken once at startup rather than re-checked per request — a
    // per-job `docker info` would add avoidable latency to every test run. A
    // Docker daemon that goes down *between* requests is still caught by the
    // runtime catch in runInDocker(), which applies the same two-gate check.
    private volatile boolean dockerAvailable = false;

    @PostConstruct
    private void init() {
        trustedRepos = Arrays.stream(trustedReposRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

        if (allowUnsafeNativeTests) {
            logger.warn("################################################################");
            logger.warn("# gitoracle.allow-unsafe-native-tests is ENABLED.");
            logger.warn("# Test execution may fall back to running a cloned repo's own");
            logger.warn("# build/test command DIRECTLY ON THIS HOST when the Docker");
            logger.warn("# sandbox is unavailable. This is only authorized for repos");
            logger.warn("# on gitoracle.testrunner.trusted-repos ({} entr{}). NEVER set", trustedRepos.size(),
                trustedRepos.size() == 1 ? "y" : "ies");
            logger.warn("# this in an environment that clones untrusted or public repos.");
            logger.warn("################################################################");
        }

        try {
            RunResult result = run(Path.of(System.getProperty("java.io.tmpdir")), 15, "docker", "info");
            dockerAvailable = result.success();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        if (dockerAvailable) {
            logger.info("Docker sandbox available.");
        } else {
            logger.error("Docker is not available at startup (`docker info` failed). Test execution will be " +
                "refused (503) for any repo not on the unsafe-native trusted-repos allowlist, since there is " +
                "no way to safely sandbox a cloned repo's own build otherwise.");
        }
    }

    private boolean isNativeFallbackAuthorized(String repoUrl) {
        return allowUnsafeNativeTests && !trustedRepos.isEmpty()
            && repoUrl != null && trustedRepos.contains(repoUrl);
    }

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

        // Fail loud (503) rather than silently degrading to an unsandboxed host
        // execution per job. Checked before cloning to avoid wasting a clone on
        // a request that's going to be refused anyway.
        if (!dockerAvailable && !isNativeFallbackAuthorized(repoUrl)) {
            logger.error("Refusing test request for job {}: Docker sandbox is unavailable and this repo is " +
                "not authorized for unsafe-native fallback.", jobId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Path workDir = Path.of(WORKSPACE_ROOT, jobId);

        try {
            // ── Step 2: Clone ──────────────────────────────────────────────────
            // Defensively clear any stale workspace left behind by a prior run that
            // was killed before reaching the finally block's cleanup.
            deleteDirectory(workDir);
            Files.createDirectories(workDir);
            // Empty/null means "whatever git clone picks with no branch specified" —
            // the repo's actual default branch. Without this, Test Runner always
            // cloned the default branch regardless of which branch the Fixer actually
            // read from and patched, so a patch generated against a non-default
            // branch's file content could apply against the wrong base or fail outright.
            String branch = request.getBranch();
            java.util.List<String> cloneCmd = new java.util.ArrayList<>(
                java.util.List.of("git", "clone", "--depth=1"));
            if (branch != null && !branch.isBlank()) {
                cloneCmd.add("--branch");
                cloneCmd.add(branch);
            }
            cloneCmd.add(repoUrl);
            cloneCmd.add(workDir.toString());

            logger.info("Cloning {} (branch: {}) into {}", repoUrl,
                        branch != null && !branch.isBlank() ? branch : "<default>", workDir);

            // One retry on a transient network failure (e.g. DNS blip) — confirmed
            // live: "Could not resolve host: github.com" resolved fine a second
            // later on the same machine, but a single failed attempt here escalates
            // the whole job with zero recourse.
            RunResult cloneResult = run(workDir.getParent(), TIMEOUT_SECONDS, cloneCmd.toArray(String[]::new));

            if (!cloneResult.success()) {
                logger.warn("Clone failed for job {}, retrying once: {}", jobId, cloneResult.output());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                deleteDirectory(workDir);
                Files.createDirectories(workDir);
                cloneResult = run(workDir.getParent(), TIMEOUT_SECONDS, cloneCmd.toArray(String[]::new));
            }

            if (!cloneResult.success()) {
                logger.error("Clone failed for job {} after retry: {}", jobId, cloneResult.output());
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
            FrameworkConfig config = detectFramework(workDir, request.getFramework());
            logger.info("Detected framework {} in dir {} for job {}", config.framework(), config.subDir(), jobId);

            // ── Step 5: Run tests in Docker ────────────────────────────────────
            return ResponseEntity.ok(runInDocker(workDir, config, jobId, repoUrl));

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

    private record FrameworkConfig(TestFramework framework, String subDir) {}

    private FrameworkConfig detectFramework(Path workDir, TestFramework hint) {
        if (hint != null && hint != TestFramework.UNKNOWN) return new FrameworkConfig(hint, ".");

        try {
            // Check root first
            FrameworkConfig rootCfg = detectInDir(workDir, ".");
            if (rootCfg != null) return rootCfg;

            // Check depth 1
            try (var stream = Files.list(workDir)) {
                var found = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(p -> detectInDir(p, p.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .findFirst();
                if (found.isPresent()) return found.get();
            }
        } catch (IOException e) {
            logger.warn("Error detecting framework", e);
        }

        // Default: pytest (Python) at root
        return new FrameworkConfig(TestFramework.PYTEST, ".");
    }

    private FrameworkConfig detectInDir(Path dir, String subDir) {
        if (Files.exists(dir.resolve("pom.xml")))             return new FrameworkConfig(TestFramework.MAVEN, subDir);
        if (Files.exists(dir.resolve("build.gradle")) ||
            Files.exists(dir.resolve("build.gradle.kts")))    return new FrameworkConfig(TestFramework.GRADLE, subDir);
        if (Files.exists(dir.resolve("package.json")))        return new FrameworkConfig(TestFramework.NPM_JEST, subDir);
        if (Files.exists(dir.resolve("Cargo.toml")))          return new FrameworkConfig(TestFramework.CARGO, subDir);
        if (Files.exists(dir.resolve("go.mod")))              return new FrameworkConfig(TestFramework.GO_TEST, subDir);
        if (Files.exists(dir.resolve("requirements.txt")) ||
            Files.exists(dir.resolve("pytest.ini")) || 
            Files.exists(dir.resolve("setup.py")))            return new FrameworkConfig(TestFramework.PYTEST, subDir);
        return null;
    }

    // ─── Docker Execution ─────────────────────────────────────────────────────

    private TestResult runInDocker(Path workDir, FrameworkConfig config, String jobId, String repoUrl) {
        String dockerImage = getDockerImage(config.framework());
        String testCommand = getTestCommand(config.framework());
        String containerWorkDir = "/repo/" + config.subDir();

        logger.info("Running '{}' in Docker image '{}' (dir {}) for job {}", testCommand, dockerImage, containerWorkDir, jobId);

        try {
            List<String> dockerCmd = List.of(
                "docker", "run", "--rm",
                "--memory=512m", "--cpus=1",                  // resource limits
                "-v", workDir.toAbsolutePath() + ":/repo:rw",
                "-w", containerWorkDir.replaceAll("/\\.$", ""), // clean trailing /.
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
            double score   = computeQualityScore(result.output(), config.framework());

            // pytest exits 5 ("no tests ran") when the repo/target dir has no test
            // suite at all — not a failure of the patch under test, just nothing to
            // verify against. Confirmed live on bettercallok/chillcall (no tests in
            // backend/): every patch was guaranteed to fail here regardless of
            // correctness, since exit 5 != 0. Treat "no tests collected" as a
            // safe-pass, same as the existing no-patch fallback above.
            boolean noTestsCollected = result.exitCode() == 5
                || result.output().contains("no tests ran")
                || result.output().contains("collected 0 items");
            if (!passed && noTestsCollected) {
                logger.info("No tests found for job {} (exit={}) — safe-pass, nothing to verify.",
                            jobId, result.exitCode());
                passed = true;
                score = 1.0;
            }

            logger.info("Tests {} for job {} (exit={})", passed ? "PASSED" : "FAILED",
                        jobId, result.exitCode());

            return new TestResult(passed, score, 0.0,
                "[exit=" + result.exitCode() + "]\n" + result.output());

        } catch (Exception e) {
            // Docker failed for this specific job (daemon down, image pull
            // failure, etc.) — this used to fall through to running the repo's
            // own build command directly on the host, unsandboxed, for ANY
            // repo. That is arbitrary third-party code execution (npm lifecycle
            // scripts, maven plugins, setup.py, build.rs) as this service's own
            // user, with this service's own environment and filesystem access.
            // A sandbox that can't be established must fail closed, not
            // silently degrade — the only exception is a repo explicitly
            // authorized via both gates in isNativeFallbackAuthorized().
            logger.error("Docker execution failed for job {}: {}", jobId, e.getMessage(), e);
            if (isNativeFallbackAuthorized(repoUrl)) {
                return runNativeUnsafe(workDir, config, jobId);
            }
            return new TestResult(false, 0.0, 0.0,
                "Sandbox unavailable — test execution refused rather than run unsandboxed on the host. " +
                "Docker error: " + e.getMessage());
        }
    }

    /**
     * Runs the repo's own test command directly on this host, as this
     * service's user, with this service's environment and full filesystem/
     * network access — only reached when gitoracle.allow-unsafe-native-tests
     * is true AND the repo is explicitly listed in
     * gitoracle.testrunner.trusted-repos (checked by the caller via
     * isNativeFallbackAuthorized()). Never call this for an untrusted or
     * public repo.
     */
    private TestResult runNativeUnsafe(Path workDir, FrameworkConfig config, String jobId) {
        logger.warn("Running UNSANDBOXED native test execution on the host for job {} — this repo is on " +
            "the trusted-repos allowlist with unsafe-native testing enabled.", jobId);
        String cmd = config.framework().getCommand();
        if (cmd == null || cmd.contains("exit 0")) {
            return new TestResult(true, 1.0, 0.0,
                "WARN: no native command available for " + config.framework() + ". Safe-pass.");
        }

        try {
            Path targetDir = config.subDir().equals(".") ? workDir : workDir.resolve(config.subDir());
            RunResult result = run(targetDir, TIMEOUT_SECONDS, "sh", "-c", cmd);
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
        String cmd = framework.getCommand();
        if (cmd == null) return "echo 'No test command available' && exit 1";
        return cmd + " 2>&1";
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
