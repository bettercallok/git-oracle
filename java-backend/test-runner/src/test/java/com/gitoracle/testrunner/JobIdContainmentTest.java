package com.gitoracle.testrunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jobId} names the directory runTest deletes recursively before cloning
 * and again when it finishes. It was joined onto the workspace root unchecked,
 * so {@code "jobId": "../victim"} deleted a directory outside it.
 */
class JobIdContainmentTest {

    @TempDir
    Path tmp;

    private TestRunnerController controller;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        controller = new TestRunnerController();
        root = Files.createDirectories(tmp.resolve("workspaces"));
        setField("workspaceRoot", root.toString());
        // Past the "Docker unavailable" 503, so the request reaches the workspace step.
        setField("dockerAvailable", true);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = TestRunnerController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    void aTraversingJobIdDoesNotDeleteADirectoryOutsideTheWorkspaceRoot() throws Exception {
        Path victim = Files.createDirectories(tmp.resolve("victim"));
        Files.writeString(victim.resolve("important.txt"), "keep me");

        TestRequest request = new TestRequest();
        request.setJobId("../victim");
        request.setRepoUrl("https://github.com/acme/app");

        ResponseEntity<TestResult> response = controller.runTest(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(victim.resolve("important.txt")).exists().hasContent("keep me");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../x", "a/b", "/etc", ".hidden", "a\\b", "", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}) // 65 chars
    void rejectsAnythingButAPlainIdentifier(String jobId) {
        assertThat(controller.workspaceFor(jobId)).isNull();
    }

    @Test
    void acceptsTheIdsThePipelineActuallySends() {
        assertThat(controller.workspaceFor("9e09ccf6-1c2b-4f3e-9a7d-0123456789ab"))
            .isEqualTo(root.toAbsolutePath().normalize().resolve("9e09ccf6-1c2b-4f3e-9a7d-0123456789ab"));
        assertThat(controller.workspaceFor("mock-test-1")).isNotNull();
        assertThat(controller.workspaceFor("debug-job")).isNotNull();
    }
}
