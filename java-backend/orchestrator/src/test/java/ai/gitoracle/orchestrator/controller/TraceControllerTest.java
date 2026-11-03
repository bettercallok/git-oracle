package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.security.TestTenantContext;
import ai.gitoracle.orchestrator.service.LangfuseClient;
import ai.gitoracle.orchestrator.service.TraceViews;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The dashboard's in-app trace view. A tenant reads only its own jobs' traces,
 * the Langfuse secret key is used server-side only, and "Langfuse is off/down"
 * is distinguishable from "this job has no trace".
 */
class TraceControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String TRACE = """
        {"id":"%s","timestamp":"2026-11-03T12:00:00.000Z","observations":[
          {"id":"g2","type":"GENERATION","name":"planner","metadata":{"agent":"planner"},
           "startTime":"2026-11-03T12:00:05.000Z","endTime":"2026-11-03T12:00:07.500Z","model":"m",
           "usage":{"input":100,"output":20,"total":120},"level":"DEFAULT",
           "input":[{"role":"system","content":"plan it"},{"role":"user","content":"bug"}],
           "output":"{\\"strategy\\":\\"x\\"}"},
          {"id":"g1","type":"GENERATION","name":"llm_structured",
           "startTime":"2026-11-03T12:00:00.000Z","endTime":"2026-11-03T12:00:02.000Z","model":"m",
           "usage":{"input":50,"output":10,"total":60},"level":"ERROR","statusMessage":"bad json",
           "input":[{"role":"system","content":"You MUST return ONLY valid JSON matching this schema: {\\"title\\": \\"InvestigationResult\\"}"}],
           "output":"oops"},
          {"id":"s1","type":"SPAN","name":"ignored","startTime":"2026-11-03T12:00:01.000Z"}
        ]}""";

    private AgentJobRepository jobs;
    private MockRestServiceServer langfuseApi;
    private TraceController controller;
    private UUID jobId;

    @BeforeEach
    void setUp() throws Exception {
        jobs = mock(AgentJobRepository.class);
        RestTemplate rest = new RestTemplate();
        langfuseApi = MockRestServiceServer.createServer(rest);
        controller = new TraceController(client(rest, "pk-lf-real", "sk-lf-real"), jobs, mock(EntityManager.class));

        jobId = UUID.randomUUID();
        AgentJob job = new AgentJob();
        job.setId(jobId);
        job.setTenantId(TENANT);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        TestTenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TestTenantContext.clear();
    }

    private static LangfuseClient client(RestTemplate rest, String pk, String sk) throws Exception {
        Constructor<LangfuseClient> c = LangfuseClient.class.getDeclaredConstructor(
            RestTemplate.class, String.class, String.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(rest, "http://langfuse.test:3000", "3002", pk, sk);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsTheJobsCallsInOrderLabelledByAgentWithTimingAndTokens() {
        langfuseApi.expect(requestTo("http://langfuse.test:3000/api/public/traces/" + jobId))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic cGstbGYtcmVhbDpzay1sZi1yZWFs")) // pk-lf-real:sk-lf-real
            .andRespond(withSuccess(TRACE.formatted(jobId), MediaType.APPLICATION_JSON));

        Map<String, Object> body = controller.jobTrace(jobId).getBody();

        langfuseApi.verify();
        assertThat(body).containsEntry("status", "OK");
        TraceViews.Trace trace = (TraceViews.Trace) body.get("trace");
        assertThat(trace.calls()).extracting(TraceViews.Call::agent).containsExactly("investigator", "planner");
        TraceViews.Call planner = trace.calls().get(1);
        assertThat(planner.offsetMs()).isEqualTo(5_000);
        assertThat(planner.latencyMs()).isEqualTo(2_500);
        assertThat(planner.input()).extracting(TraceViews.Message::role).containsExactly("system", "user");
        assertThat(trace.calls().get(0).level()).isEqualTo("ERROR");
        assertThat(trace.summary().calls()).isEqualTo(2);
        assertThat(trace.summary().totalTokens()).isEqualTo(180);
        assertThat(trace.summary().llmTimeMs()).isEqualTo(4_500);
        assertThat(trace.summary().wallClockMs()).isEqualTo(7_500);
        assertThat(trace.summary().errors()).isEqualTo(1);
    }

    @Test
    void anotherTenantsJobIsNotFoundAndLangfuseIsNeverAsked() {
        TestTenantContext.set(UUID.randomUUID());
        langfuseApi.expect(never(), requestTo("http://langfuse.test:3000/api/public/traces/" + jobId));

        assertThat(controller.jobTrace(jobId).getStatusCode().value()).isEqualTo(404);
        langfuseApi.verify();
    }

    @Test
    void aJobWithNoTraceYetSaysSo() {
        langfuseApi.expect(requestTo("http://langfuse.test:3000/api/public/traces/" + jobId))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(controller.jobTrace(jobId).getBody()).containsEntry("status", "NO_TRACE");
    }

    @Test
    void langfuseDownIsReportedAsUnavailableNotAsMissing() {
        langfuseApi.expect(requestTo("http://langfuse.test:3000/api/public/traces/" + jobId))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(controller.jobTrace(jobId).getBody()).containsEntry("status", "UNAVAILABLE");
    }

    @Test
    void placeholderKeysMeanNotConfiguredAndNoCallIsMade() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer api = MockRestServiceServer.createServer(rest);
        TraceController unconfigured = new TraceController(client(rest, "pk-lf-...", "sk-lf-..."), jobs, mock(EntityManager.class));

        assertThat(unconfigured.jobTrace(jobId).getBody()).containsEntry("status", "NOT_CONFIGURED");
        api.verify();
    }

    @Test
    void longPromptsAreTruncatedAndFlagged() {
        String huge = "x".repeat(TraceViews_MAX + 10);
        String trace = """
            {"id":"t","observations":[{"id":"g","type":"GENERATION","name":"fixer",
              "startTime":"2026-11-03T12:00:00Z","endTime":"2026-11-03T12:00:01Z",
              "input":[{"role":"user","content":"%s"}],"output":"ok"}]}""".formatted(huge);
        langfuseApi.expect(requestTo("http://langfuse.test:3000/api/public/traces/" + jobId))
            .andRespond(withSuccess(trace, MediaType.APPLICATION_JSON));

        TraceViews.Trace view = (TraceViews.Trace) controller.jobTrace(jobId).getBody().get("trace");
        List<TraceViews.Message> input = view.calls().get(0).input();
        assertThat(input.get(0).truncated()).isTrue();
        assertThat(input.get(0).content()).hasSize(TraceViews_MAX);
    }

    private static final int TraceViews_MAX = 60_000;
}
