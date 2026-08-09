package ai.gitoracle.orchestrator;

import ai.gitoracle.core.model.postgres.AgentJob;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins AgentJob's @Column annotations against the exact regression hit live on job
 * 6e08cd53 (bettercallok/chillcall eval case1_npe): fixPatch had no columnDefinition,
 * so Hibernate created it as the JPA default varchar(255). Persisting a unified diff
 * (routinely >255 chars) failed with "value too long for type character
 * varying(255)", which rolled back the whole handleFixGenerated transaction — the
 * job never left QUEUED — and threw out of the Kafka listener, which burned all of
 * Spring Kafka's default 10 retries and discarded the event, stranding the job
 * permanently.
 *
 * This can't be caught by a unit test that merely calls setFixPatch()/getFixPatch()
 * — a plain String field has no length limit in Java, so that would pass whether or
 * not the column is wide enough. Only the annotation itself carries the constraint
 * Hibernate will apply, so that's what has to be pinned.
 */
class AgentJobSchemaTest {

    @Test
    void fixPatchColumnIsUnboundedText() throws NoSuchFieldException {
        Field field = AgentJob.class.getDeclaredField("fixPatch");
        Column column = field.getAnnotation(Column.class);

        assertThat(column)
            .as("fixPatch must have an explicit @Column so Hibernate doesn't fall back "
                + "to the JPA default of varchar(255), which is far narrower than a real "
                + "unified diff and fails silently at persist time")
            .isNotNull();
        assertThat(column.columnDefinition())
            .as("fixPatch's columnDefinition must be an unbounded text type, not left "
                + "empty (which defaults to varchar(255))")
            .isEqualToIgnoringCase("TEXT");
    }

    @Test
    void fixPatchCanHoldARealisticUnifiedDiff() {
        // A same-order sanity check on the Java side: nothing in AgentJob itself
        // truncates or rejects a patch this size. Doesn't touch Hibernate/Postgres —
        // the actual width enforcement is pinned by the annotation test above.
        String diff = "--- a/src/main/java/com/example/UserService.java\n"
            + "+++ b/src/main/java/com/example/UserService.java\n"
            + "@@ -8,6 +8,9 @@\n".repeat(20);
        assertThat(diff.length()).isGreaterThan(255);

        AgentJob job = new AgentJob();
        job.setFixPatch(diff);

        assertThat(job.getFixPatch()).isEqualTo(diff);
    }
}
