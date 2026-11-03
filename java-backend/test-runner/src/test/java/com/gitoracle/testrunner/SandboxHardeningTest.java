package com.gitoracle.testrunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for C3: hardening the Docker sandbox test execution
 * runs in. buildDockerRunCommand() and its cache-mount helpers are the
 * actual security-relevant surface (what flags a real `docker run` gets),
 * so they're exercised directly here rather than only through a live
 * container — cheap to run everywhere, and this is exactly the kind of
 * change where a single dropped flag silently reopens the hole.
 *
 * A live end-to-end check against real Docker (network isolation, actual
 * non-root uid inside the container, read-only-root enforcement, each
 * framework's dependency install still working under it) still matters and
 * was done manually against a running instance — that's not something a
 * fast unit test can substitute for, especially the two cases fixed only
 * after a live run caught them: buildDockerRunCommand()'s scratch space
 * being disk-backed rather than tmpfs (a tmpfs /tmp OOM'd on this repo's
 * own real Gradle wrapper download once memory-accounted), and
 * ensureSandboxNetwork()'s create-path actually setting sandboxNetworkReady.
 */
class SandboxHardeningTest {

    private TestRunnerController controller;

    @TempDir
    Path workDir;

    @BeforeEach
    void setUp() {
        controller = new TestRunnerController();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = TestRunnerController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private List<String> build(TestRunnerController.FrameworkConfig config) throws Exception {
        return controller.buildDockerRunCommand(workDir, config, "/repo/.", "some-image@sha256:abc", "some test command");
    }

    // ─── The flags that actually matter ────────────────────────────────────

    @Test
    void neverRunsAsRoot_defaultsToFallbackNonRootUser() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).containsSubsequence("--user", "65534:65534");
    }

    @Test
    void usesResolvedNonRootUserWhenSet() throws Exception {
        setField("sandboxUser", "1000:1000");
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).containsSubsequence("--user", "1000:1000");
        assertThat(cmd).doesNotContain("65534:65534");
    }

    @Test
    void rootFilesystemIsReadOnly() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).contains("--read-only");
    }

    @Test
    void dropsAllCapabilitiesAndBlocksPrivilegeEscalation() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).contains("--cap-drop=ALL");
        assertThat(cmd).containsSubsequence("--security-opt", "no-new-privileges");
    }

    @Test
    void boundsProcessCountAgainstForkBombs() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).contains("--pids-limit=256");
    }

    @Test
    void boundsMemoryWithNoSwapEscapeHatch() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).contains("--memory=512m");
        assertThat(cmd).contains("--memory-swap=512m");
    }

    @Test
    void usesDedicatedNetworkWhenReady() throws Exception {
        setField("sandboxNetworkReady", true);
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).containsSubsequence("--network", "gitoracle-sandbox-net");
    }

    @Test
    void fallsBackToBridgeNetworkWhenSandboxNetworkUnavailable() throws Exception {
        setField("sandboxNetworkReady", false);
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).containsSubsequence("--network", "bridge");
    }

    // ─── Scratch space is disk-backed, not tmpfs (regression: OOM'd otherwise) ─

    @Test
    void tmpIsADiskBackedBindMount_notTmpfs() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).noneMatch(arg -> arg.equals("--tmpfs"));
        boolean hasTmpBindMount = cmd.stream().anyMatch(arg -> arg.endsWith(":/tmp:rw"));
        assertThat(hasTmpBindMount).isTrue();
    }

    @Test
    void tmpDirIsActuallyCreatedOnDisk() throws Exception {
        build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(Files.isDirectory(workDir.resolve(".sandbox-scratch").resolve("tmp"))).isTrue();
    }

    @Test
    void homeIsRedirectedIntoScratchSpace() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).containsSubsequence("-e", "HOME=/tmp");
    }

    // ─── Framework-specific cache mounts: only where actually needed ──────────

    @Test
    void cargoGetsWritableRegistryAndGitCacheMounts() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.CARGO, "."));
        assertThat(cmd).anyMatch(arg -> arg.endsWith(":/usr/local/cargo/registry:rw"));
        assertThat(cmd).anyMatch(arg -> arg.endsWith(":/usr/local/cargo/git:rw"));
        assertThat(Files.isDirectory(workDir.resolve(".sandbox-scratch").resolve("cargo-registry"))).isTrue();
    }

    @Test
    void goGetsWritableModuleCacheMount() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.GO_TEST, "."));
        assertThat(cmd).anyMatch(arg -> arg.endsWith(":/go/pkg:rw"));
    }

    @Test
    void frameworksThatDontNeedItGetNoExtraCacheMounts() throws Exception {
        for (TestFramework fw : List.of(TestFramework.PYTEST, TestFramework.MAVEN,
                TestFramework.GRADLE, TestFramework.NPM_JEST)) {
            List<String> cmd = build(new TestRunnerController.FrameworkConfig(fw, "."));
            assertThat(cmd).noneMatch(arg -> arg.contains("/usr/local/cargo") || arg.contains("/go/pkg"));
        }
    }

    // ─── The actual repo bind mount and working directory still get through ───

    @Test
    void repoIsBindMountedWritable() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        boolean hasRepoMount = cmd.stream().anyMatch(arg -> arg.endsWith(":/repo:rw"));
        assertThat(hasRepoMount).isTrue();
    }

    @Test
    void imageAndTestCommandStillReachTheContainer() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        assertThat(cmd).contains("some-image@sha256:abc");
        assertThat(cmd).containsSubsequence("sh", "-c");

        // The command is no longer the bare string: H9 prefixes it with a guard
        // that refuses to run if /repo arrived empty (see
        // guardEmptyWorkspace). The assertion is therefore "the command still
        // reaches the container" rather than "the command is the whole
        // argument" — an empty /repo used to be reported as a PASS.
        String shellArg = cmd.get(cmd.size() - 1);
        assertThat(shellArg).contains("some test command");
        assertThat(shellArg).endsWith("some test command");
    }

    @Test
    void theShellArgumentGuardsAgainstAnEmptyRepoMount() throws Exception {
        List<String> cmd = build(new TestRunnerController.FrameworkConfig(TestFramework.PYTEST, "."));
        String shellArg = cmd.get(cmd.size() - 1);

        assertThat(shellArg).contains("ls -A /repo");
        assertThat(shellArg).contains("exit 91");
        assertThat(shellArg.indexOf("ls -A /repo")).isLessThan(shellArg.indexOf("some test command"));
    }

    // ─── Dependency cache: persists per (tenant, repository) ──────────────

    private List<String> buildWithCache(TestFramework framework, String cacheKey) throws Exception {
        setField("workspaceRoot", workDir.toString());
        return controller.buildDockerRunCommand(workDir, new TestRunnerController.FrameworkConfig(framework, "."),
            "/repo/.", "some-image@sha256:abc", "some test command", cacheKey);
    }

    @Test
    void mavenGetsAPersistentRepositoryCacheUnderTheWorkspaceRoot() throws Exception {
        setField("dependencyCacheEnabled", true);
        String key = controller.dependencyCacheKey("tenant-a", "https://github.com/o/r");
        List<String> cmd = buildWithCache(TestFramework.MAVEN, key);

        String mount = workDir.resolve(".dep-cache").resolve(key).resolve("m2").toAbsolutePath() + ":/tmp/.m2:rw";
        assertThat(cmd).containsSubsequence("-v", mount);
        assertThat(cmd).contains("--read-only");               // the cache doesn't loosen anything else
    }

    @Test
    void cachesAreSeparatePerTenantAndPerRepository() throws Exception {
        setField("dependencyCacheEnabled", true);
        String a = controller.dependencyCacheKey("tenant-a", "https://github.com/o/r");

        assertThat(controller.dependencyCacheKey("tenant-b", "https://github.com/o/r")).isNotEqualTo(a);
        assertThat(controller.dependencyCacheKey("tenant-a", "https://github.com/o/other")).isNotEqualTo(a);
        assertThat(controller.dependencyCacheKey("tenant-a", "https://github.com/O/R.git")).isEqualTo(a);
        assertThat(a).matches("[0-9a-f]{24}");                 // path-safe, never the raw URL
    }

    @Test
    void noCacheWhenDisabledOrWithoutARepository() throws Exception {
        setField("dependencyCacheEnabled", false);
        assertThat(controller.dependencyCacheKey("t", "https://github.com/o/r")).isNull();
        setField("dependencyCacheEnabled", true);
        assertThat(controller.dependencyCacheKey("t", " ")).isNull();

        List<String> cmd = buildWithCache(TestFramework.MAVEN, null);
        assertThat(String.join(" ", cmd)).doesNotContain("/tmp/.m2");
    }
}
