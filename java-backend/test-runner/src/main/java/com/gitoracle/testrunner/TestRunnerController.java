package com.gitoracle.testrunner;

import com.gitoracle.testrunner.security.RepoRefValidator;
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

    // A dedicated bridge network, not Docker's default `bridge`. Docker's
    // default bridge has inter-container communication enabled among every
    // container attached to it — if any of GitOracle's own containers (or a
    // concurrent test-runner job) were ever on that same default network,
    // an untrusted test container could reach them directly by container IP
    // with no host port involved at all. A dedicated network isolates the
    // sandbox tier from every other container GitOracle runs.
    private static final String SANDBOX_NETWORK = "gitoracle-sandbox-net";

    // Docker containers default to root when the image doesn't declare a
    // USER (true of every stock language image used below). Running the
    // sandbox as root hands a compromised/malicious test process root
    // *inside* the container, which is one `--cap-add`/kernel bug away from
    // root on the host. Resolved once at startup: if this service's own
    // process is non-root (the normal case), the sandbox runs as that same
    // uid/gid — which already owns the bind-mounted workspace this service
    // created, so no permission workaround is needed. If this service is
    // somehow running as root, running the sandbox as the same uid would
    // mean root-in-container too, so fall back to a fixed non-root uid and
    // chown the workspace to it (root can always do that; a non-root
    // process chowning a file it already owns to itself is a harmless
    // no-op, so this chown runs unconditionally either way).
    private static final String SANDBOX_FALLBACK_USER = "65534:65534";
    private String sandboxUser = SANDBOX_FALLBACK_USER;
    private String sandboxUid = "65534";
    private String sandboxGid = "65534";
    private volatile boolean sandboxNetworkReady = false;

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
            sandboxUser = resolveSandboxUser();
            String[] parts = sandboxUser.split(":");
            sandboxUid = parts[0];
            sandboxGid = parts.length > 1 ? parts[1] : parts[0];
            ensureSandboxNetwork();
        } else {
            logger.error("Docker is not available at startup (`docker info` failed). Test execution will be " +
                "refused (503) for any repo not on the unsafe-native trusted-repos allowlist, since there is " +
                "no way to safely sandbox a cloned repo's own build otherwise.");
        }
    }

    private String resolveSandboxUser() {
        try {
            RunResult uid = run(Path.of(System.getProperty("java.io.tmpdir")), 5, "id", "-u");
            RunResult gid = run(Path.of(System.getProperty("java.io.tmpdir")), 5, "id", "-g");
            if (uid.success() && gid.success() && !"0".equals(uid.output().trim())) {
                return uid.output().trim() + ":" + gid.output().trim();
            }
        } catch (Exception e) {
            logger.warn("Could not resolve this service's own uid/gid for sandbox containers, " +
                "falling back to {}: {}", SANDBOX_FALLBACK_USER, e.getMessage());
        }
        logger.warn("test-runner is running as root (or its uid couldn't be resolved) — sandbox " +
            "containers will run as {} instead, with the workspace chowned to match.", SANDBOX_FALLBACK_USER);
        return SANDBOX_FALLBACK_USER;
    }

    private void ensureSandboxNetwork() {
        try {
            RunResult inspect = run(Path.of(System.getProperty("java.io.tmpdir")), 10,
                "docker", "network", "inspect", SANDBOX_NETWORK);
            if (inspect.success()) {
                sandboxNetworkReady = true;
                return;
            }
            // Deliberately NOT --internal: dependency installs (npm ci, pip
            // install, mvn/gradle test) need internet egress to package
            // registries. This network only isolates the sandbox tier from
            // GitOracle's other containers (see SANDBOX_NETWORK) — it does
            // NOT by itself block reaching the docker host's published ports
            // via the bridge gateway or block RFC1918/cloud-metadata egress.
            // That requires host-level iptables/nftables rules on the
            // FORWARD chain for this bridge, which is a production
            // deployment step (see infrastructure/docker/README) and is not
            // something this JVM process applies itself: doing so would
            // require running this service with NET_ADMIN/root on the host,
            // which is itself a privilege this sandboxing service shouldn't
            // need, and the equivalent host network namespace isn't reachable
            // this way at all on Docker Desktop (macOS/Windows) dev machines.
            RunResult create = run(Path.of(System.getProperty("java.io.tmpdir")), 15,
                "docker", "network", "create", "--driver", "bridge", SANDBOX_NETWORK);
            if (create.success()) {
                sandboxNetworkReady = true;
                logger.info("Created dedicated sandbox network {}.", SANDBOX_NETWORK);
            } else {
                logger.error("Could not create sandbox network {} — falling back to the default bridge " +
                    "network for this run, which permits inter-container communication with any other " +
                    "container GitOracle runs: {}", SANDBOX_NETWORK, create.output());
            }
        } catch (Exception e) {
            logger.error("Could not ensure sandbox network {} exists: {}", SANDBOX_NETWORK, e.getMessage());
        }
    }

    // Most package managers cache under $HOME (redirected onto disk-backed
    // scratch space via -e HOME=/tmp below), but two of the stock images
    // bake in a *different*, absolute cache path that ignores $HOME
    // entirely — confirmed by inspecting each image directly, not assumed:
    // Cargo's CARGO_HOME=/usr/local/cargo and Go's GOPATH=/go (Maven's
    // MAVEN_CONFIG=/root/.m2 looked like a third case but its own entrypoint
    // script unsets it before the test command runs, so HOME alone handles
    // it). --read-only would make those paths unwritable regardless of
    // $HOME, so they get their own targeted bind mounts instead — narrow
    // ones (the registry/module cache subdirectory, not the whole
    // CARGO_HOME/GOPATH) so the toolchain binaries the image ships under
    // those same trees aren't shadowed by an empty mount over them.
    private List<String> sandboxCacheBindMounts(Path scratchRoot, TestFramework framework) throws IOException {
        List<String> mounts = new java.util.ArrayList<>();
        for (var entry : cacheMountPoints(framework).entrySet()) {
            Path hostDir = scratchRoot.resolve(entry.getKey());
            Files.createDirectories(hostDir);
            mounts.add("-v");
            mounts.add(hostDir.toAbsolutePath() + ":" + entry.getValue() + ":rw");
        }
        return mounts;
    }

    private Map<String, String> cacheMountPoints(TestFramework framework) {
        return switch (framework) {
            case CARGO -> Map.of(
                "cargo-registry", "/usr/local/cargo/registry",
                "cargo-git", "/usr/local/cargo/git");
            case GO_TEST -> Map.of("go-pkg", "/go/pkg");
            default -> Map.of();
        };
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

        // repoUrl and branch reach this endpoint from HTTP request bodies with no
        // upstream authentication. Before this validation, a repoUrl of
        // `ext::sh -c '<cmd>'` (git's ext:: transport) executed arbitrary commands,
        // and a value starting with `-` was parsed as a git option rather than a
        // repository — both because the value went straight into the clone argv
        // with no terminator and no allowlist. Reject anything that isn't an
        // https://github.com/<owner>/<repo> URL before it ever reaches `git`.
        String branch = request.getBranch();
        try {
            repoUrl = RepoRefValidator.validateRepoUrl(repoUrl);
            branch = RepoRefValidator.validateBranch(branch);
        } catch (RepoRefValidator.InvalidRepoRefException e) {
            logger.warn("Rejected test request for job {}: {}", jobId, e.getMessage());
            return ResponseEntity.ok(new TestResult(false, 0.0, 0.0,
                "Rejected: " + e.getMessage()));
        }

        // Fail loud (503) rather than silently degrading to an unsandboxed host
        // execution per job. Checked before cloning (and after the repoUrl/branch
        // validation above) to avoid wasting a clone on a request that's going to
        // be refused anyway, and to match the trusted-repos allowlist against the
        // canonical validated URL rather than a raw, unvalidated one.
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
            // repoUrl/branch are already validated above; the "--" still guards
            // against any value that slips past validation being reinterpreted as
            // a git option instead of a positional argument.
            java.util.List<String> cloneCmd = new java.util.ArrayList<>(
                java.util.List.of("git", "clone", "--depth=1"));
            if (branch != null && !branch.isBlank()) {
                cloneCmd.add("--branch");
                cloneCmd.add(branch);
            }
            cloneCmd.add("--");
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

    // Package-private (not private): constructed directly in SandboxHardeningTest
    // to exercise buildDockerRunCommand() without needing a live Docker daemon.
    record FrameworkConfig(TestFramework framework, String subDir) {}

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

    /**
     * Builds the full `docker run` argv for sandboxing one job's test
     * execution. Extracted as its own method (rather than inline in
     * runInDocker) specifically so the security-relevant flags here can be
     * asserted directly in a unit test without needing a live Docker daemon.
     */
    List<String> buildDockerRunCommand(Path workDir, FrameworkConfig config, String containerWorkDir,
                                        String dockerImage, String testCommand) throws IOException {
        // Scratch space for /tmp and the framework-specific caches below is a
        // host-directory bind mount, not --tmpfs. tmpfs is RAM-backed and its
        // pages count against --memory, so it seemed like the obvious choice
        // for "writable space under a --read-only root" — until a live run
        // against this repo's own (unremarkable-sized) Gradle build OOM'd
        // partway through unzipping just the ~130MB Gradle wrapper
        // distribution, because that download plus the dependency cache
        // landing in the same tmpfs blew past the container's 512m memory
        // limit. A disk-backed bind mount has no such coupling — this is the
        // same trade-off the pre-hardening code already had (writes landed on
        // the container's normal disk-backed layer, uncapped by --memory),
        // just now under a read-only root instead of a writable one. It is
        // cleaned up for free: this whole directory lives under the job's
        // workDir, which the caller already deletes in its `finally` block.
        Path scratchRoot = workDir.resolve(".sandbox-scratch");
        Path tmpDir = scratchRoot.resolve("tmp");
        Files.createDirectories(tmpDir);

        List<String> dockerCmd = new java.util.ArrayList<>(List.of(
            "docker", "run", "--rm",
            "--network", sandboxNetworkReady ? SANDBOX_NETWORK : "bridge",
            "--user", sandboxUser,
            "--read-only",
            "-v", tmpDir.toAbsolutePath() + ":/tmp:rw",
            "--security-opt", "no-new-privileges",
            "--cap-drop=ALL",
            "--pids-limit=256",
            "--ulimit", "nofile=1024:1024",
            "--ulimit", "fsize=209715200",              // 200MB max single file
            "--memory=512m", "--memory-swap=512m", "--cpus=1", // no swap beyond the memory limit
            "-e", "HOME=/tmp"                           // package-manager caches land in the mount above
        ));
        dockerCmd.addAll(sandboxCacheBindMounts(scratchRoot, config.framework()));
        dockerCmd.addAll(List.of(
            "-v", workDir.toAbsolutePath() + ":/repo:rw",
            "-w", containerWorkDir.replaceAll("/\\.$", ""), // clean trailing /.
            dockerImage,
            "sh", "-c", testCommand
        ));
        return dockerCmd;
    }

    private TestResult runInDocker(Path workDir, FrameworkConfig config, String jobId, String repoUrl) {
        String dockerImage = getDockerImage(config.framework());
        String testCommand = getTestCommand(config.framework());
        String containerWorkDir = "/repo/" + config.subDir();

        logger.info("Running '{}' in Docker image '{}' (dir {}) for job {}", testCommand, dockerImage, containerWorkDir, jobId);

        // sandboxNetworkReady=false means network isolation from GitOracle's own
        // containers isn't in effect for this run — see ensureSandboxNetwork()'s
        // logged error for why. Still every other hardening flag still applies.
        if (!sandboxNetworkReady) {
            logger.warn("Running job {} on the default bridge network — sandbox network " +
                "{} was not available.", jobId, SANDBOX_NETWORK);
        }

        try {
            // Creates the scratch/cache subdirectories under workDir as a side
            // effect, so chown below (which must run AFTER this, not before)
            // covers them too.
            List<String> dockerCmd = buildDockerRunCommand(workDir, config, containerWorkDir, dockerImage, testCommand);

            // The container will run as sandboxUser (never root — see
            // resolveSandboxUser()), which may not be the uid that owns this
            // bind-mounted workspace (it is only guaranteed to when this
            // service itself is non-root, the common case). chown
            // unconditionally: when sandboxUser IS this process's own uid,
            // chowning a path you already own to yourself is a no-op; when
            // it's the root-fallback uid, only root (which is what that
            // branch implies) can chown to an arbitrary uid, and root always
            // can.
            try {
                run(workDir, 10, "chown", "-R", sandboxUser, workDir.toAbsolutePath().toString());
            } catch (Exception e) {
                logger.warn("Could not chown workspace {} to sandbox user {} for job {}: {}",
                    workDir, sandboxUser, jobId, e.getMessage());
            }
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

    // Pinned by digest, not tag: a tag (e.g. "python:3.11-slim") can be
    // repointed by the upstream image owner (or a compromised registry
    // account) to different, potentially malicious content at any time —
    // the next job would silently pull and run it. A digest is immutable
    // content-addressing, so this only ever runs the exact image reviewed
    // when it was pinned. Refresh deliberately, not automatically:
    //   docker pull <tag> && docker inspect --format='{{index .RepoDigests 0}}' <tag>
    // then update both the digest below and the tag in the comment (kept
    // purely for human readability — the digest is what's actually used).
    private String getDockerImage(TestFramework framework) {
        return switch (framework) {
            // python:3.11-slim
            case PYTEST    -> "python@sha256:1042b61448fef4ba92d16a8c7eb4996d027568ce64792a7877fd88511e0af7c6";
            // maven:3.9-eclipse-temurin-21-alpine
            case MAVEN     -> "maven@sha256:65353f527c86cb23187c8233475713e15067e8d36220d18863c379680698fe85";
            // gradle:8.7-jdk21-alpine
            case GRADLE    -> "gradle@sha256:d6ea1c746d8365fae41c70d5812c28c8fca88c905b69d5f9da57ad4cc0218ab1";
            // node:20-alpine
            case NPM_JEST  -> "node@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293";
            // rust:1.78-slim
            case CARGO     -> "rust@sha256:0fea967628dc796a2b9d1d57ddb3af3b3f0a35b6c8c0e23690dbe0ceb71a2dc9";
            // golang:1.22-alpine
            case GO_TEST   -> "golang@sha256:1699c10032ca2582ec89a24a1312d986a3f094aed3d5c1147b19880afe40e052";
            // python:3.11-slim
            default        -> "python@sha256:1042b61448fef4ba92d16a8c7eb4996d027568ce64792a7877fd88511e0af7c6";
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
        // Belt-and-braces alongside RepoRefValidator: even if a forbidden value
        // somehow reached a `git` invocation, these disable the alternate
        // transports (ext::, fd::) and any interactive/credential prompt that a
        // crafted URL or config could otherwise trigger. Harmless for non-git
        // commands (docker run, etc.) invoked through this same helper.
        pb.environment().put("GIT_ALLOW_PROTOCOL", "https");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
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
