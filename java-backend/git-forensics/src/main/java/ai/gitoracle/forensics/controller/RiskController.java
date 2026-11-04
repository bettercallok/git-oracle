package ai.gitoracle.forensics.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class RiskController {

    private static final Logger logger = LoggerFactory.getLogger(RiskController.class);

    private final Neo4jClient neo4jClient;
    private final RestTemplate restTemplate;

    @Value("${gitoracle.orchestrator-url:http://localhost:8083}")
    private String orchestratorUrl;

    @Value("${gitoracle.internal-token:}")
    private String internalToken;

    public RiskController(Neo4jClient neo4jClient) {
        this(neo4jClient, new RestTemplate());
    }

    RiskController(Neo4jClient neo4jClient, RestTemplate restTemplate) {
        this.neo4jClient = neo4jClient;
        this.restTemplate = restTemplate;
    }

    /**
     * Bug-fix hotspots and per-developer bug rates — for the caller's own
     * repositories only. This used to query the whole graph, so any tenant saw
     * every analysed repository's files and developers. The graph's Commit and
     * File nodes carry their repo slug; the caller's allow-list comes from the
     * orchestrator (the gateway sets X-Tenant-ID from the API key).
     */
    @GetMapping("/risk")
    public ResponseEntity<Map<String, Object>> getRiskHeatmap(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        List<String> repos;
        try {
            repos = allowedRepos(tenantId);
        } catch (RestClientException e) {
            logger.warn("Could not fetch the repository allow-list for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "The repository allow-list is unavailable; try again shortly."));
        }
        if (repos.isEmpty()) {
            return ResponseEntity.ok(Map.of("files", List.of(), "developers", List.of(), "repos", List.of()));
        }

        // Query files modified by bug fixes
        String fileQuery =
            "MATCH (c:Commit {isBugFix: true})-[:MODIFIED]->(f:File) " +
            "WHERE toLower(f.repo) IN $repos " +
            "RETURN f.path AS file, f.repo AS repo, count(c) AS bugs " +
            "ORDER BY bugs DESC LIMIT 20";

        Collection<Map<String, Object>> files = neo4jClient.query(fileQuery).bind(repos).to("repos").fetch().all()
            .stream().map(row -> {
                long bugs = (Long) row.get("bugs");
                String heat = bugs > 10 ? "critical" : bugs > 5 ? "high" : bugs > 2 ? "medium" : "low";
                return Map.of("file", row.get("file"), "repo", row.get("repo"), "bugs", bugs, "heat", heat);
            }).collect(Collectors.toList());

        // Query developers, counting only their commits in these repositories
        String devQuery =
            "MATCH (d:Developer)-[:AUTHORED]->(c:Commit) " +
            "WHERE toLower(c.repo) IN $repos " +
            "WITH d.name AS name, count(c) AS commits, sum(CASE WHEN c.isBugFix = true THEN 1 ELSE 0 END) AS bugs " +
            "RETURN name, commits, toFloat(bugs) / commits AS bugRate " +
            "ORDER BY bugRate DESC LIMIT 20";

        Collection<Map<String, Object>> developers = neo4jClient.query(devQuery).bind(repos).to("repos").fetch().all()
            .stream().map(row -> Map.of(
                "name", row.get("name"),
                "commits", row.get("commits"),
                "bugRate", row.get("bugRate")
            )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("files", files, "developers", developers, "repos", repos));
    }

    /** The caller's allow-list from the orchestrator ({@code GET /api/v1/repos}), lower-cased. */
    @SuppressWarnings("unchecked")
    List<String> allowedRepos(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-Tenant-ID", tenantId);
        }
        Map<String, Object> body = restTemplate.exchange(orchestratorUrl + "/api/v1/repos", HttpMethod.GET,
            new HttpEntity<>(headers), Map.class).getBody();
        Object repos = body == null ? null : body.get("repos");
        if (!(repos instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(r -> String.valueOf(r).toLowerCase(Locale.ROOT)).toList();
    }
}
