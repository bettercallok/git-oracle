package ai.gitoracle.forensics.controller;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class RiskController {

    private final Neo4jClient neo4jClient;

    public RiskController(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @GetMapping("/risk")
    public ResponseEntity<Map<String, Object>> getRiskHeatmap() {
        // Query files modified by bug fixes
        String fileQuery = 
            "MATCH (c:Commit {isBugFix: true})-[:MODIFIED]->(f:File) " +
            "RETURN f.path AS file, count(c) AS bugs " +
            "ORDER BY bugs DESC LIMIT 20";
            
        Collection<Map<String, Object>> files = neo4jClient.query(fileQuery).fetch().all()
            .stream().map(row -> {
                long bugs = (Long) row.get("bugs");
                String heat = bugs > 10 ? "critical" : bugs > 5 ? "high" : bugs > 2 ? "medium" : "low";
                return Map.of("file", row.get("file"), "bugs", bugs, "heat", heat);
            }).collect(Collectors.toList());

        // Query developers
        String devQuery = 
            "MATCH (d:Developer)-[:AUTHORED]->(c:Commit) " +
            "WITH d.name AS name, count(c) AS commits, sum(CASE WHEN c.isBugFix = true THEN 1 ELSE 0 END) AS bugs " +
            "RETURN name, commits, toFloat(bugs) / commits AS bugRate " +
            "ORDER BY bugRate DESC LIMIT 20";
            
        Collection<Map<String, Object>> developers = neo4jClient.query(devQuery).fetch().all()
            .stream().map(row -> Map.of(
                "name", row.get("name"),
                "commits", row.get("commits"),
                "bugRate", row.get("bugRate")
            )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("files", files, "developers", developers));
    }
}
