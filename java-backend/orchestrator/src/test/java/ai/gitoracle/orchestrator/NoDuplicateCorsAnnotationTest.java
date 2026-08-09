package ai.gitoracle.orchestrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the invariant that no orchestrator controller carries @CrossOrigin.
 *
 * Confirmed live: once the dashboard was pointed at the API Gateway (:8080) so
 * Risk Heatmap could reach git-forensics, every endpoint on a controller that
 * *also* had its own @CrossOrigin started failing in the browser with
 * "Access-Control-Allow-Origin header contains multiple values
 * 'http://localhost:5173, http://localhost:5173'" — the gateway's globalcors
 * config and the controller's own @CrossOrigin were each adding a copy of the
 * header, and browsers reject a duplicated header outright even when both
 * copies are identical. curl doesn't enforce CORS, so every curl-based check
 * done earlier that session missed it entirely; it only showed up in an actual
 * browser console.
 *
 * RiskController (a different module, git-forensics) never had this annotation,
 * which is exactly why /risk worked immediately while /jobs and /evals didn't —
 * so this test is scoped to the orchestrator module only. TestRunnerController
 * legitimately keeps @CrossOrigin (a separate module that isn't reached through
 * the gateway) and is out of scope here for the same reason.
 *
 * A source scan rather than a runtime HTTP test: reproducing the duplicate-header
 * failure at runtime needs a live gateway + orchestrator + browser-grade CORS
 * enforcement, none of which a unit test has. What actually matters — that nobody
 * re-adds the annotation — is fully captured by checking the source doesn't
 * contain it.
 */
class NoDuplicateCorsAnnotationTest {

    @Test
    void noOrchestratorControllerDeclaresCrossOrigin() throws IOException {
        Path sourceRoot = Path.of("src/main/java/ai/gitoracle/orchestrator");
        assertThat(sourceRoot)
            .as("expected to run with the orchestrator module directory as CWD")
            .isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    // Comments explaining *why* there's no @CrossOrigin are fine and
                    // expected (see e.g. DashboardController) — only flag a line that
                    // isn't a comment and actually names the annotation.
                    if (!line.startsWith("//") && line.contains("@CrossOrigin")) {
                        violations.add(file + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }

        assertThat(violations)
            .as("An orchestrator controller declared @CrossOrigin. The API Gateway's "
                + "globalcors config already sets Access-Control-Allow-Origin for every "
                + "/api/v1/** route reaching the orchestrator — a second copy here breaks "
                + "CORS entirely rather than being redundant. See DashboardController's "
                + "class-level comment for the full explanation.")
            .isEmpty();
    }
}
