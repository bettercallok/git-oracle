package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a real sandbox Job, against a cluster you point it at. Skipped unless
 * GITORACLE_K8S_IT_URL is set. Expects, in namespace GITORACLE_K8S_IT_NAMESPACE
 * (default gitoracle): the test-runner Role from deploy/k8s, a claim named
 * gitoracle-test-workspaces whose directory "it-job" holds a file "hello"
 * containing "hi", and GITORACLE_K8S_IT_TOKEN / GITORACLE_K8S_IT_CA naming the
 * service-account token and CA files.
 */
@EnabledIfEnvironmentVariable(named = "GITORACLE_K8S_IT_URL", matches = ".+")
class KubernetesJobSandboxClusterIT {

    @Test
    void aJobRunsLockedDownAgainstTheSharedWorkspace() throws Exception {
        String namespace = System.getenv().getOrDefault("GITORACLE_K8S_IT_NAMESPACE", "gitoracle");
        KubeApi api = KubeApi.of(System.getenv("GITORACLE_K8S_IT_URL"),
            Path.of(System.getenv("GITORACLE_K8S_IT_TOKEN")), Path.of(System.getenv("GITORACLE_K8S_IT_CA")));
        Path root = Path.of("/workspaces");
        var sandbox = new KubernetesJobSandbox(api,
            new KubernetesJobSandbox.Settings(namespace, "gitoracle-test-workspaces", root, null));
        assertThat(sandbox.available()).isTrue();

        // What the pod sees: the workspace, its own uid, a read-only root, and a writable /tmp.
        String command = "cat /repo/hello; id -u; "
            + "if touch /not-writable 2>/dev/null; then echo root-writable; else echo root-read-only; fi; "
            + "touch /tmp/ok && echo tmp-writable; exit 3";
        var result = sandbox.run("it-job", "busybox:1.36", "/repo", command,
            List.of(new KubernetesJobSandbox.Mount(root.resolve("it-job"), "/repo"),
                    new KubernetesJobSandbox.Mount(root.resolve("it-job/.sandbox-scratch/tmp"), "/tmp")),
            "10001", "10001", 60);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).contains("hi", "10001", "root-read-only", "tmp-writable");
    }
}
