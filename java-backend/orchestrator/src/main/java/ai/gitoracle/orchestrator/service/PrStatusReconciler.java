package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.OrchestratorService;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Notices when a PR GitOracle opened is merged or closed.
 *
 * <p>The error-ingestor already turns GitHub's {@code pull_request: closed}
 * webhook into a PR outcome, but GitHub can't deliver webhooks to a local
 * deployment, and none ever arrived: chillcall#18 was closed without merging
 * and job 9e09ccf6 kept showing "PR opened". This asks GitHub instead, through
 * github-bot, for every job still in PR_OPENED, and records what it finds the
 * same way the webhook does — so the two can't disagree, and whichever reports
 * an outcome second is a no-op.
 */
@Component
@ConditionalOnProperty(prefix = "gitoracle.pr-reconciler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrStatusReconciler {

    private static final Logger logger = LoggerFactory.getLogger(PrStatusReconciler.class);

    private final GitHubInstallations installations;
    private final AgentJobRepository jobRepository;
    private final OrchestratorService orchestrator;
    private final RestTemplate restTemplate;
    private final String githubBotUrl;
    private final String internalToken;

    @Autowired
    public PrStatusReconciler(AgentJobRepository jobRepository, OrchestratorService orchestrator,
                              GitHubInstallations installations,
                              @Value("${gitoracle.github-bot-url:http://localhost:8085}") String githubBotUrl,
                              @Value("${gitoracle.internal-token:}") String internalToken) {
        this(jobRepository, orchestrator, installations, new RestTemplate(), githubBotUrl, internalToken);
    }

    PrStatusReconciler(AgentJobRepository jobRepository, OrchestratorService orchestrator,
                       GitHubInstallations installations, RestTemplate restTemplate,
                       String githubBotUrl, String internalToken) {
        this.installations = installations;
        this.jobRepository = jobRepository;
        this.orchestrator = orchestrator;
        this.restTemplate = restTemplate;
        this.githubBotUrl = githubBotUrl;
        this.internalToken = internalToken;
    }

    @Scheduled(fixedDelayString = "${gitoracle.pr-reconciler.interval:PT3M}",
               initialDelayString = "PT30S")
    public void reconcileOpenPullRequests() {
        for (AgentJob job : jobRepository.findByState("PR_OPENED")) {
            if (!EscalationService.hasPullRequest(job)) continue;
            try {
                reconcile(job);
            } catch (Exception e) {
                // One unreachable PR (deleted repo, revoked install, GitHub down)
                // must not stop the rest; it is simply asked again next time.
                logger.warn("Could not check PR {} for job {}: {}", job.getPrUrl(), job.getId(), e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void reconcile(AgentJob job) {
        String url = UriComponentsBuilder.fromHttpUrl(githubBotUrl + "/pull-request/status")
            .queryParam("url", job.getPrUrl())
            .queryParam("installationId", installations.require(job.getTenantId()))
            .encode()
            .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> status = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class)
            .getBody();
        if (status == null || !"closed".equals(status.get("state"))) return;

        String outcome = Boolean.TRUE.equals(status.get("merged")) ? "MERGED" : "CLOSED";
        Object by = status.get("by");
        logger.info("PR {} for job {} is {} — recording it", job.getPrUrl(), job.getId(), outcome.toLowerCase());
        orchestrator.recordPrOutcome(job.getId(), outcome, by == null ? "unknown" : by.toString(), job.getPrUrl());
    }
}
