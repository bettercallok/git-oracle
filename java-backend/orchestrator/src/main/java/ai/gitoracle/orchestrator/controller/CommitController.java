package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.service.GitHubClientService;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * Commit Explorer REST API — backs the new "Commit Explorer" dashboard section.
 *
 * Endpoints:
 *   GET  /api/v1/commits?repo={owner/repo}&page={n}&per_page={n}
 *        Returns a paginated list of commits for the given repository.
 *
 *   GET  /api/v1/commits/{sha}/diff?repo={owner/repo}
 *        Returns the full changed-file list and unified diff for a single commit.
 *
 *   POST /api/v1/commits/{sha}/analyze?repo={owner/repo}
 *        Body: { "question": "...", "chatHistory": [{"role":"user","content":"..."},...] }
 *        Fetches the commit diff, then proxies everything to the commit_analyst
 *        Python agent (port 9004) and streams back its answer.
 */
@RestController
@RequestMapping("/api/v1/commits")
@CrossOrigin(origins = "${gitoracle.allowed-origins}", allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CommitController {

    private static final Logger logger = LoggerFactory.getLogger(CommitController.class);
    private static final int DEFAULT_PER_PAGE = 30;
    private static final int MAX_PER_PAGE     = 100;
    private static final String COMMIT_ANALYST_URL = "http://localhost:9004/analyze";

    private final GitHubClientService githubClientService;
    private final RestTemplate restTemplate = new RestTemplate();

    public CommitController(GitHubClientService githubClientService) {
        this.githubClientService = githubClientService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/commits?repo=owner/repo&page=1&per_page=30
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listCommits(
            @RequestParam String repo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage) {

        if (repo == null || repo.isBlank() || !repo.contains("/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }

        int safePage    = Math.max(1, page);
        int safePerPage = Math.min(Math.max(1, perPage), MAX_PER_PAGE);

        logger.info("Listing commits for {} (page={}, perPage={})", repo, safePage, safePerPage);

        try {
            GitHub github = githubClientService.getAuthenticatedClient();
            GHRepository ghRepo = github.getRepository(repo);

            // Fetch commits — GitHub Java SDK paginates automatically via PagedIterable
            PagedIterable<GHCommit> pagedCommits = ghRepo.listCommits();

            // Skip to the requested page manually (0-indexed internally)
            int skip = (safePage - 1) * safePerPage;

            List<Map<String, Object>> commits = StreamSupport
                    .stream(pagedCommits.spliterator(), false)
                    .skip(skip)
                    .limit(safePerPage)
                    .map(commit -> {
                        try {
                            GHCommit.ShortInfo info = commit.getCommitShortInfo();
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("sha",       commit.getSHA1());
                            entry.put("shortSha",  commit.getSHA1().substring(0, 7));
                            entry.put("message",   info.getMessage());
                            // Trim to first line for list view
                            entry.put("shortMessage", info.getMessage().split("\n")[0]);
                            entry.put("author",    info.getAuthor().getName());
                            entry.put("authorEmail", info.getAuthor().getEmail());
                            entry.put("date",      info.getAuthor().getDate() != null
                                    ? info.getAuthor().getDate().toInstant().toString()
                                    : null);
                            // Stats are only available in the full commit object
                            try {
                                GHCommit fullCommit = ghRepo.getCommit(commit.getSHA1());
                                entry.put("filesChanged", fullCommit.getFiles().size());
                                entry.put("additions",    fullCommit.getLinesAdded());
                                entry.put("deletions",    fullCommit.getLinesDeleted());
                                entry.put("totalChanges", fullCommit.getLinesChanged());
                            } catch (Exception statsEx) {
                                entry.put("filesChanged", 0);
                                entry.put("additions",    0);
                                entry.put("deletions",    0);
                                entry.put("totalChanges", 0);
                            }
                            return entry;
                        } catch (Exception e) {
                            logger.warn("Could not map commit {}: {}", commit.getSHA1(), e.getMessage());
                            return Map.<String, Object>of("sha", commit.getSHA1(), "error", e.getMessage());
                        }
                    })
                    .toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("repo",    repo);
            response.put("page",    safePage);
            response.put("perPage", safePerPage);
            response.put("count",   commits.size());
            response.put("commits", commits);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("404")) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Repository not found or the GitHub App is not installed on this repo.",
                        "repo",  repo,
                        "hint",  "Make sure the GitOracle GitHub App is installed on this repository."));
            }
            logger.error("GitHub API error listing commits for {}: {}", repo, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "GitHub API error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error listing commits for {}: {}", repo, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/commits/{sha}/diff?repo=owner/repo
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{sha}/diff")
    public ResponseEntity<?> getCommitDiff(
            @PathVariable String sha,
            @RequestParam String repo) {

        if (repo == null || repo.isBlank() || !repo.contains("/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }

        logger.info("Fetching diff for commit {} in {}", sha, repo);

        try {
            GitHub github = githubClientService.getAuthenticatedClient();
            GHRepository ghRepo  = github.getRepository(repo);
            GHCommit commit      = ghRepo.getCommit(sha);
            GHCommit.ShortInfo info = commit.getCommitShortInfo();

            // Build a rich file-level diff response
            List<Map<String, Object>> files = commit.getFiles().stream().map(file -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("filename",   file.getFileName());
                f.put("status",     file.getStatus());     // added, modified, removed, renamed
                f.put("additions",  file.getLinesAdded());
                f.put("deletions",  file.getLinesDeleted());
                f.put("changes",    file.getLinesChanged());
                f.put("patch",      file.getPatch());      // unified diff for this file
                return f;
            }).toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sha",          sha);
            response.put("shortSha",     sha.length() >= 7 ? sha.substring(0, 7) : sha);
            response.put("repo",         repo);
            response.put("message",      info.getMessage());
            response.put("shortMessage", info.getMessage().split("\n")[0]);
            response.put("author",       info.getAuthor().getName());
            response.put("authorEmail",  info.getAuthor().getEmail());
            response.put("date",         info.getAuthor().getDate() != null
                    ? info.getAuthor().getDate().toInstant().toString()
                    : null);
            response.put("additions",    commit.getLinesAdded());
            response.put("deletions",    commit.getLinesDeleted());
            response.put("totalChanges", commit.getLinesChanged());
            response.put("filesChanged", files.size());
            response.put("files",        files);

            return ResponseEntity.ok(response);


        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("404")) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Commit not found. Check the SHA and repo name.",
                        "sha",   sha,
                        "repo",  repo));
            }
            logger.error("GitHub API error fetching diff for {}/{}: {}", repo, sha, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "GitHub API error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error fetching diff for {}/{}: {}", repo, sha, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/commits/{sha}/analyze?repo=owner/repo
    // Body: { "question": "...", "chatHistory": [...] }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{sha}/analyze")
    public ResponseEntity<?> analyzeCommit(
            @PathVariable String sha,
            @RequestParam String repo,
            @RequestBody Map<String, Object> body) {

        if (repo == null || repo.isBlank() || !repo.contains("/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }

        String question = (String) body.getOrDefault("question", "");
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question field is required"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> chatHistory =
                (List<Map<String, String>>) body.getOrDefault("chatHistory", List.of());

        logger.info("Analyzing commit {} in {} — question: '{}'", sha, repo, question);

        // ── Step 1: Fetch the commit diff from GitHub ──────────────────────
        String combinedDiff;
        String commitMessage;
        try {
            GitHub github = githubClientService.getAuthenticatedClient();
            GHRepository ghRepo = github.getRepository(repo);
            GHCommit commit     = ghRepo.getCommit(sha);
            commitMessage       = commit.getCommitShortInfo().getMessage().split("\n")[0];

            // Build a compact unified diff string from all changed files
            StringBuilder diffBuilder = new StringBuilder();
            for (GHCommit.File file : commit.getFiles()) {
                diffBuilder.append("--- a/").append(file.getFileName()).append("\n");
                diffBuilder.append("+++ b/").append(file.getFileName()).append("\n");
                diffBuilder.append("Status: ").append(file.getStatus())
                           .append(" (+").append(file.getLinesAdded())
                           .append(" -").append(file.getLinesDeleted()).append(")\n");
                if (file.getPatch() != null) {
                    diffBuilder.append(file.getPatch()).append("\n");
                }
                diffBuilder.append("\n");
            }
            combinedDiff = diffBuilder.toString();

            // Truncate to ~8000 chars to stay within reasonable LLM context limits
            if (combinedDiff.length() > 8000) {
                combinedDiff = combinedDiff.substring(0, 8000) + "\n... [diff truncated for context window]";
            }
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("404")) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Commit not found.", "sha", sha, "repo", repo));
            }
            logger.error("GitHub API error fetching diff for analyze {}/{}: {}", repo, sha, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "GitHub API error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error fetching diff for analyze: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }

        // ── Step 2: Forward to the Python Commit Analyst agent ────────────
        try {
            Map<String, Object> agentPayload = new LinkedHashMap<>();
            agentPayload.put("sha",          sha);
            agentPayload.put("repo",         repo);
            agentPayload.put("commitMessage", commitMessage);
            agentPayload.put("diff",         combinedDiff);
            agentPayload.put("question",     question);
            agentPayload.put("chatHistory",  chatHistory);

            @SuppressWarnings("unchecked")
            Map<String, Object> agentResponse =
                    restTemplate.postForObject(COMMIT_ANALYST_URL, agentPayload, Map.class);

            if (agentResponse == null) {
                return ResponseEntity.status(502).body(Map.of(
                        "error", "Commit Analyst agent returned an empty response."));
            }

            logger.info("Commit Analyst answered for {}/{}", repo, sha);
            return ResponseEntity.ok(agentResponse);

        } catch (ResourceAccessException e) {
            // Python agent is not running
            logger.error("Could not reach Commit Analyst agent at {}: {}", COMMIT_ANALYST_URL, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Commit Analyst agent is not available. Start it with: cd ai_core && python -m agents.commit_analyst.main",
                    "details", e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error calling Commit Analyst for {}/{}: {}", repo, sha, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
