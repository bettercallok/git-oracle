package com.gitoracle.testrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Kubernetes sandbox keeps every limit the Docker sandbox applies. */
class KubernetesJobSandboxTest {

    private static final Path ROOT = Path.of("/workspaces");
    private static final KubernetesJobSandbox.Settings SETTINGS =
        new KubernetesJobSandbox.Settings("gitoracle", "gitoracle-test-workspaces", ROOT, "node-1");
    private static final String JOB = "0b8a6f5e-1c2d-4e3f-8a9b-0c1d2e3f4a5b";

    private static Map<String, Object> spec() {
        return KubernetesJobSandbox.jobSpec("gitoracle-test-x", JOB, "python@sha256:abc", "/repo",
            "pytest", List.of(
                new KubernetesJobSandbox.Mount(ROOT.resolve(JOB), "/repo"),
                new KubernetesJobSandbox.Mount(ROOT.resolve(JOB + "/.sandbox-scratch/tmp"), "/tmp"),
                new KubernetesJobSandbox.Mount(ROOT.resolve(".dep-cache/abc/pip"), "/tmp/.cache/pip")),
            "10001", "10001", 180, SETTINGS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> map, String... keys) {
        Map<String, Object> current = map;
        for (String key : keys) current = (Map<String, Object>) current.get(key);
        return current;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSandboxPodIsLockedDown() {
        Map<String, Object> pod = at(spec(), "spec", "template", "spec");
        Map<String, Object> container = ((List<Map<String, Object>>) pod.get("containers")).get(0);

        assertThat(pod.get("automountServiceAccountToken")).isEqualTo(false);
        assertThat(pod.get("restartPolicy")).isEqualTo("Never");
        assertThat(at(pod, "securityContext")).containsEntry("runAsNonRoot", true).containsEntry("runAsUser", 10001L);
        assertThat(at(container, "securityContext"))
            .containsEntry("allowPrivilegeEscalation", false)
            .containsEntry("readOnlyRootFilesystem", true)
            .containsEntry("privileged", false)
            .containsEntry("capabilities", Map.of("drop", List.of("ALL")));
        assertThat(at(container, "resources", "limits")).containsEntry("memory", "512Mi").containsEntry("cpu", "1");
        assertThat(container.get("command")).isEqualTo(List.of("sh", "-c", "pytest"));
        assertThat(at(pod, "nodeSelector")).containsEntry("kubernetes.io/hostname", "node-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJobCleansUpAndIsFoundByTheNetworkPolicyLabel() {
        Map<String, Object> job = at(spec(), "spec");
        assertThat(job).containsEntry("backoffLimit", 0)
            .containsEntry("activeDeadlineSeconds", 180 + KubernetesJobSandbox.START_ALLOWANCE_SECONDS)
            .containsEntry("ttlSecondsAfterFinished", 600);
        Map<String, Object> labels = at(job, "template", "metadata", "labels");
        assertThat(labels).containsEntry("app.kubernetes.io/component", "test-sandbox");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyMountIsASubPathOfTheSharedClaim() {
        Map<String, Object> pod = at(spec(), "spec", "template", "spec");
        var mounts = (List<Map<String, Object>>) ((List<Map<String, Object>>) pod.get("containers")).get(0).get("volumeMounts");
        assertThat(mounts).extracting(m -> m.get("subPath"))
            .containsExactly(JOB, JOB + "/.sandbox-scratch/tmp", ".dep-cache/abc/pip");
        assertThat(pod.get("volumes")).isEqualTo(List.of(Map.of("name", "workspace",
            "persistentVolumeClaim", Map.of("claimName", "gitoracle-test-workspaces"))));

        assertThatThrownBy(() -> KubernetesJobSandbox.subPath(ROOT, Path.of("/etc")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KubernetesJobSandbox.subPath(ROOT, ROOT.resolve("../etc")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KubernetesJobSandbox.subPath(ROOT, ROOT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jobNamesAreValidDnsLabels() {
        for (String id : new String[] {JOB, "Some_Job_ID", "-x-", null}) {
            String name = KubernetesJobSandbox.jobName(id);
            assertThat(name).matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?").hasSizeLessThanOrEqualTo(63);
        }
    }

    @Test
    void dockerVolumeArgumentsBecomeMounts() {
        assertThat(KubernetesJobSandbox.fromDockerVolumes(List.of("-v", "/workspaces/a:/tmp/.m2:rw")))
            .containsExactly(new KubernetesJobSandbox.Mount(Path.of("/workspaces/a"), "/tmp/.m2"));
    }

    // ── The run loop, against a fake API server ────────────────────────────────

    private static final class FakeApi extends KubeApi {
        final List<String> calls = new ArrayList<>();
        String podState = "{\"terminated\":{\"exitCode\":0}}";
        String jobConditions = "[]";

        FakeApi() { super("https://fake", null, null); }

        @Override Response get(String path) {
            calls.add("GET " + path);
            if (path.contains("/pods?labelSelector=")) {
                return new Response(200, "{\"items\":[{\"metadata\":{\"name\":\"pod-1\"},\"status\":"
                    + "{\"containerStatuses\":[{\"name\":\"test\",\"state\":" + podState + "}]}}]}");
            }
            if (path.endsWith("/log?container=test&limitBytes=1048576")) return new Response(200, "3 passed in 0.1s");
            return new Response(200, "{\"status\":{\"conditions\":" + jobConditions + "}}");
        }
        @Override Response post(String path, String json) {
            calls.add("POST " + path);
            return new Response(201, "{}");
        }
        @Override Response delete(String path) {
            calls.add("DELETE " + path);
            return new Response(200, "{}");
        }
    }

    private static TestRunnerController.RunResult runWith(FakeApi api) throws Exception {
        return new KubernetesJobSandbox(api, SETTINGS, 1).run(JOB, "python@sha256:abc", "/repo", "pytest",
            List.of(new KubernetesJobSandbox.Mount(ROOT.resolve(JOB), "/repo")), "10001", "10001", 180);
    }

    @Test
    void aFinishedPodGivesItsExitCodeAndLogsAndTheJobIsDeleted() throws Exception {
        FakeApi api = new FakeApi();
        api.podState = "{\"terminated\":{\"exitCode\":1}}";

        var result = runWith(api);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("3 passed in 0.1s");
        assertThat(result.timedOut()).isFalse();
        assertThat(api.calls.get(0)).isEqualTo("POST /apis/batch/v1/namespaces/gitoracle/jobs");
        assertThat(api.calls.get(api.calls.size() - 1))
            .startsWith("DELETE /apis/batch/v1/namespaces/gitoracle/jobs/gitoracle-test-")
            .endsWith("?propagationPolicy=Background");
    }

    @Test
    void aJobPastItsDeadlineIsATimeout() throws Exception {
        FakeApi api = new FakeApi();
        api.podState = "{\"running\":{}}";
        api.jobConditions = "[{\"type\":\"Failed\",\"reason\":\"DeadlineExceeded\"}]";

        assertThat(runWith(api).timedOut()).isTrue();
    }

    @Test
    void aPodThatCannotStartFailsClosed() {
        FakeApi api = new FakeApi();
        api.podState = "{\"waiting\":{\"reason\":\"ImagePullBackOff\",\"message\":\"not found\"}}";

        assertThatThrownBy(() -> runWith(api)).isInstanceOf(IOException.class).hasMessageContaining("ImagePullBackOff");
        assertThat(api.calls.get(api.calls.size() - 1)).startsWith("DELETE ");
    }

    @Test
    void theSpecIsValidJson() throws Exception {
        String json = new ObjectMapper().writeValueAsString(spec());
        assertThat(new ObjectMapper().readTree(json).path("kind").asText()).isEqualTo("Job");
    }
}
