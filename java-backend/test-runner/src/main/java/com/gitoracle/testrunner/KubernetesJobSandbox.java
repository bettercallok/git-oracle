package com.gitoracle.testrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs one job's tests as a Kubernetes Job instead of {@code docker run}.
 *
 * <p>Nodes in a cluster have no Docker daemon, and a privileged Docker-in-Docker
 * pod would be a bigger hole than the one it fills. Each test run is instead a
 * short-lived pod with the same limits the Docker sandbox applies: non-root,
 * no privilege escalation, every capability dropped, a read-only root, 512Mi of
 * memory and one CPU, no service-account token, and a NetworkPolicy (label
 * {@code app.kubernetes.io/component=test-sandbox}, in deploy/k8s) that blocks
 * the cluster and leaves only the internet for package downloads.
 *
 * <p>The workspace this service cloned and patched lives on a volume claim
 * shared with the sandbox pod: every directory the Docker sandbox bind-mounts
 * becomes a {@code subPath} of that claim, so nothing is copied. With a
 * ReadWriteOnce claim (k3s local-path) the pod is pinned to this service's node.
 */
class KubernetesJobSandbox {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesJobSandbox.class);
    static final String COMPONENT_LABEL = "test-sandbox";
    /** Time for the image pull and pod start, on top of the test timeout. */
    static final int START_ALLOWANCE_SECONDS = 120;
    private static final int LOG_LIMIT_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Mount(Path hostPath, String containerPath) {}

    record Settings(String namespace, String claimName, Path workspaceRoot, String nodeName) {}

    private final KubeApi api;
    private final Settings settings;
    private final long pollMillis;

    KubernetesJobSandbox(KubeApi api, Settings settings) {
        this(api, settings, 2_000);
    }

    KubernetesJobSandbox(KubeApi api, Settings settings, long pollMillis) {
        this.api = api;
        this.settings = settings;
        this.pollMillis = pollMillis;
    }

    /** True when this service may create Jobs in its namespace. */
    boolean available() {
        try {
            var response = api.get(jobsPath() + "?limit=1");
            if (!response.ok()) {
                logger.error("Kubernetes sandbox unavailable: listing Jobs in {} returned {}: {}",
                    settings.namespace(), response.status(), response.body());
            }
            return response.ok();
        } catch (Exception e) {
            logger.error("Kubernetes sandbox unavailable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Runs {@code shellCommand} in {@code image} and waits for it. A run that
     * outlives {@code timeoutSeconds} plus the start allowance comes back as
     * timed out. A pod that never starts (bad image, quota) throws, so the
     * caller fails closed exactly as it does when Docker breaks.
     */
    TestRunnerController.RunResult run(String jobId, String image, String workingDir, String shellCommand,
                                       List<Mount> mounts, String uid, String gid, int timeoutSeconds)
            throws IOException, InterruptedException {
        String name = jobName(jobId);
        Map<String, Object> spec = jobSpec(name, jobId, image, workingDir, shellCommand, mounts, uid, gid,
            timeoutSeconds, settings);
        var created = api.post(jobsPath(), JSON.writeValueAsString(spec));
        if (!created.ok()) {
            throw new IOException("Could not create sandbox Job " + name + ": " + created.status() + " " + created.body());
        }
        logger.info("Started sandbox Job {} ({}) for job {}", name, image, jobId);
        try {
            return await(name, timeoutSeconds);
        } finally {
            delete(name);
        }
    }

    private TestRunnerController.RunResult await(String name, int timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds + START_ALLOWANCE_SECONDS + 30) * 1000L;
        String waitingReason = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode pod = pod(name);
            if (pod != null) {
                JsonNode state = containerState(pod);
                JsonNode terminated = state == null ? null : state.get("terminated");
                if (terminated != null) {
                    int exit = terminated.path("exitCode").asInt(-1);
                    return new TestRunnerController.RunResult(exit, logs(pod), false);
                }
                JsonNode waiting = state == null ? null : state.get("waiting");
                waitingReason = waiting == null ? null : waiting.path("reason").asText(null);
                if (waitingReason != null && FATAL_WAITING.contains(waitingReason)) {
                    throw new IOException("Sandbox pod could not start: " + waitingReason + " "
                        + waiting.path("message").asText(""));
                }
            }
            if (deadlineExceeded(name)) {
                return new TestRunnerController.RunResult(-1, pod == null ? "" : logs(pod), true);
            }
            Thread.sleep(pollMillis);
        }
        if (waitingReason != null) {
            throw new IOException("Sandbox pod did not start in time: " + waitingReason);
        }
        return new TestRunnerController.RunResult(-1, "", true);
    }

    /** Waiting reasons that will not fix themselves before the deadline. */
    private static final java.util.Set<String> FATAL_WAITING = java.util.Set.of(
        "ErrImagePull", "ImagePullBackOff", "InvalidImageName", "CreateContainerConfigError",
        "CreateContainerError");

    private JsonNode pod(String jobName) throws IOException, InterruptedException {
        String selector = URLEncoder.encode("job-name=" + jobName, StandardCharsets.UTF_8);
        var response = api.get("/api/v1/namespaces/" + settings.namespace() + "/pods?labelSelector=" + selector);
        if (!response.ok()) return null;
        JsonNode items = JSON.readTree(response.body()).path("items");
        return items.isArray() && !items.isEmpty() ? items.get(0) : null;
    }

    private static JsonNode containerState(JsonNode pod) {
        for (JsonNode status : pod.path("status").path("containerStatuses")) {
            if ("test".equals(status.path("name").asText())) return status.path("state");
        }
        return null;
    }

    private boolean deadlineExceeded(String name) throws IOException, InterruptedException {
        var response = api.get(jobsPath() + "/" + name);
        if (!response.ok()) return false;
        for (JsonNode condition : JSON.readTree(response.body()).path("status").path("conditions")) {
            if ("Failed".equals(condition.path("type").asText())
                    && "DeadlineExceeded".equals(condition.path("reason").asText())) {
                return true;
            }
        }
        return false;
    }

    private String logs(JsonNode pod) throws IOException, InterruptedException {
        String podName = pod.path("metadata").path("name").asText();
        var response = api.get("/api/v1/namespaces/" + settings.namespace() + "/pods/" + podName
            + "/log?container=test&limitBytes=" + LOG_LIMIT_BYTES);
        return response.ok() ? response.body() : "";
    }

    private void delete(String name) {
        try {
            api.delete(jobsPath() + "/" + name + "?propagationPolicy=Background");
        } catch (Exception e) {
            // ttlSecondsAfterFinished removes it anyway.
            logger.warn("Could not delete sandbox Job {}: {}", name, e.getMessage());
        }
    }

    private String jobsPath() {
        return "/apis/batch/v1/namespaces/" + settings.namespace() + "/jobs";
    }

    /** A valid, unique Job name: lowercase DNS label, at most 63 characters. */
    static String jobName(String jobId) {
        String id = jobId == null ? "job" : jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (id.length() > 36) id = id.substring(0, 36);
        id = id.replaceAll("^-+|-+$", "");
        byte[] random = new byte[4];
        new java.security.SecureRandom().nextBytes(random);
        return "gitoracle-test-" + (id.isEmpty() ? "job" : id) + "-" + HexFormat.of().formatHex(random);
    }

    /** The Job, as the API takes it. Pure, so the hardening can be tested without a cluster. */
    static Map<String, Object> jobSpec(String name, String jobId, String image, String workingDir,
                                       String shellCommand, List<Mount> mounts, String uid, String gid,
                                       int timeoutSeconds, Settings settings) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", "gitoracle");
        labels.put("app.kubernetes.io/component", COMPONENT_LABEL);
        labels.put("gitoracle.ai/job-id", jobName(jobId).replaceAll("^gitoracle-test-|-[0-9a-f]{8}$", ""));

        List<Map<String, Object>> volumeMounts = new ArrayList<>();
        for (Mount mount : mounts) {
            volumeMounts.add(Map.of(
                "name", "workspace",
                "mountPath", mount.containerPath(),
                "subPath", subPath(settings.workspaceRoot(), mount.hostPath())));
        }

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "test");
        container.put("image", image);
        container.put("command", List.of("sh", "-c", shellCommand));
        container.put("workingDir", workingDir);
        container.put("env", List.of(Map.of("name", "HOME", "value", "/tmp")));
        container.put("resources", Map.of(
            "limits", Map.of("memory", "512Mi", "cpu", "1", "ephemeral-storage", "1Gi"),
            "requests", Map.of("memory", "256Mi", "cpu", "250m")));
        container.put("securityContext", Map.of(
            "allowPrivilegeEscalation", false,
            "readOnlyRootFilesystem", true,
            "privileged", false,
            "capabilities", Map.of("drop", List.of("ALL"))));
        container.put("volumeMounts", volumeMounts);

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("enableServiceLinks", false);
        podSpec.put("terminationGracePeriodSeconds", 5);
        podSpec.put("securityContext", Map.of(
            "runAsNonRoot", true,
            "runAsUser", Long.parseLong(uid),
            "runAsGroup", Long.parseLong(gid),
            "seccompProfile", Map.of("type", "RuntimeDefault")));
        if (settings.nodeName() != null && !settings.nodeName().isBlank()) {
            // The claim is ReadWriteOnce: only pods on this node can mount it.
            podSpec.put("nodeSelector", Map.of("kubernetes.io/hostname", settings.nodeName()));
        }
        podSpec.put("containers", List.of(container));
        podSpec.put("volumes", List.of(Map.of(
            "name", "workspace",
            "persistentVolumeClaim", Map.of("claimName", settings.claimName()))));

        Map<String, Object> jobSpec = new LinkedHashMap<>();
        jobSpec.put("backoffLimit", 0);
        jobSpec.put("activeDeadlineSeconds", timeoutSeconds + START_ALLOWANCE_SECONDS);
        jobSpec.put("ttlSecondsAfterFinished", 600);
        jobSpec.put("template", Map.of("metadata", Map.of("labels", labels), "spec", podSpec));

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("apiVersion", "batch/v1");
        job.put("kind", "Job");
        job.put("metadata", Map.of("name", name, "labels", labels));
        job.put("spec", jobSpec);
        return job;
    }

    /** {@code hostPath} relative to the claim's root; anything outside it is refused. */
    static String subPath(Path workspaceRoot, Path hostPath) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path path = hostPath.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Sandbox mount " + hostPath + " is not inside " + root);
        }
        return root.relativize(path).toString();
    }

    /** Pairs of {@code -v host:container[:mode]} as the Docker mount helpers produce them. */
    static List<Mount> fromDockerVolumes(List<String> args) {
        List<Mount> mounts = new ArrayList<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            if (!"-v".equals(args.get(i))) throw new IllegalArgumentException("Expected -v, got " + args.get(i));
            String[] parts = args.get(i + 1).split(":");
            if (parts.length < 2) throw new IllegalArgumentException("Malformed volume " + args.get(i + 1));
            mounts.add(new Mount(Path.of(parts[0]), parts[1]));
        }
        return mounts;
    }
}
