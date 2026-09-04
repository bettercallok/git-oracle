package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.service.GitHubClientService;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
// No @CrossOrigin: reached only through the API Gateway, which already sets
// Access-Control-Allow-Origin globally — a duplicate here breaks CORS entirely
// (see DashboardController for the full explanation).
@RestController
@RequestMapping("/api/v1/commits")
public class CommitController {

    private static final Logger logger = LoggerFactory.getLogger(CommitController.class);
    private static final int DEFAULT_PER_PAGE = 30;
    private static final int MAX_PER_PAGE     = 100;

    /**
     * H7. `page` was clamped only at the bottom (`Math.max(1, page)`), and the
     * resulting `skip` ran against a LAZY PagedIterable — so the skipped
     * commits are not free, they are fetched from the GitHub API one page at a
     * time and thrown away. `?page=20000000` walks two billion commits: it
     * pins a request thread indefinitely and burns the installation's shared
     * GitHub API quota doing it, which degrades every tenant, not just the
     * caller.
     *
     * Larger `page * perPage` also overflows int (`(page-1) * perPage` with
     * page=100000000, perPage=100 exceeds Integer.MAX_VALUE), producing a
     * negative skip and an IllegalArgumentException from Stream.skip — a 500
     * for what is plainly bad input.
     *
     * Both bounds are enforced explicitly, and the arithmetic is done in long.
     */
    private static final int MAX_PAGE = 100;
    private static final long MAX_SKIP = 5_000;

    /**
     * Strict owner/repo. The old check was `repo.contains("/")`, which admits
     * "../../some/other/path" — and this value is interpolated into a GitHub
     * API request path by the client library, so a traversing value is an
     * attempt to address a different API resource entirely. GitHub's own naming
     * rules are far narrower than "contains a slash".
     */
    private static final java.util.regex.Pattern OWNER_REPO =
        java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}$");

    private static final String COMMIT_ANALYST_URL = "http://localhost:9004/analyze";

    private final GitHubClientService githubClientService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gitoracle.internal-token:}")
    private String internalToken;

    public CommitController(GitHubClientService githubClientService) {
        this.githubClientService = githubClientService;
    }

    private static boolean isValidRepo(String repo) {
        return repo != null && OWNER_REPO.matcher(repo).matches();
    }

    /**
     * A commit SHA goes into a GitHub API path the same way `repo` does.
     * RepoRefValidator already owns this rule (added for C1), so it is reused
     * rather than restated.
     */
    private static boolean isValidSha(String sha) {
        // validateSha treats a blank value as "not specified" and returns null
        // rather than throwing, which is right for an optional field but wrong
        // here — a blank sha must not pass a check whose whole job is to
        // confirm one was supplied. Require a non-null result explicitly.
        try {
            return ai.gitoracle.core.security.RepoRefValidator.validateSha(sha) != null;
        } catch (ai.gitoracle.core.security.RepoRefValidator.InvalidRepoRefException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/commits?repo=owner/repo&page=1&per_page=30
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listCommits(
            @RequestParam String repo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage) {

        if (!isValidRepo(repo)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }

        // Reject rather than silently clamp: a caller asking for page 100000000
        // has misunderstood something, and quietly serving them page 100 hides
        // that. Rejecting is also what makes this cheap — the whole point is to
        // answer before doing any GitHub work at all.
        if (page < 1 || page > MAX_PAGE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "page must be between 1 and " + MAX_PAGE,
                    "hint",  "Deep pagination over the commit list is not supported; narrow the range instead."));
        }

        int safePage    = page;
        int safePerPage = Math.min(Math.max(1, perPage), MAX_PER_PAGE);

        // long, not int: (page-1)*perPage overflows int at the upper end of the
        // old unbounded range and produces a negative skip.
        long skip = (long) (safePage - 1) * safePerPage;
        if (skip > MAX_SKIP) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Requested offset is too deep (" + skip + " > " + MAX_SKIP + ")",
                    "hint",  "Every skipped commit is still fetched from the GitHub API; use a narrower page/per_page."));
        }

        logger.info("Listing commits for {} (page={}, perPage={})", repo, safePage, safePerPage);

        try {
            GitHub github = githubClientService.getAuthenticatedClient();
            GHRepository ghRepo = github.getRepository(repo);

            // Fetch commits — GitHub Java SDK paginates automatically via PagedIterable
            PagedIterable<GHCommit> pagedCommits = ghRepo.listCommits();

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
                            // Stats are only available in the full commit object.
                            //
                            // NOTE (unfixed, deliberately): this is one extra
                            // GitHub API call PER COMMIT, so a single request at
                            // per_page=100 costs ~100 calls on top of the list
                            // itself. An installation's quota is 5000/hour and
                            // is SHARED ACROSS TENANTS, so roughly 50 requests
                            // here can exhaust commit browsing for everyone.
                            // The page/skip bounds above cap the damage per
                            // request but do not remove this amplification.
                            // Fixing it properly means either dropping stats
                            // from the list view or caching commit stats by SHA
                            // (they are immutable, so caching is trivially
                            // correct) — both are behaviour changes to the
                            // dashboard's data, not a validation fix, so they
                            // are not folded into this change. The per-tenant
                            // GitHub quota the plan calls for belongs with them.
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

        if (!isValidRepo(repo)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }
        if (!isValidSha(sha)) {
            return ResponseEntity.badRequest().body(Map.of("error", "sha is not a valid commit SHA"));
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
            @jakarta.validation.Valid @RequestBody ai.gitoracle.orchestrator.dto.Requests.AnalyzeCommit body) {

        if (!isValidRepo(repo)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "repo parameter must be in 'owner/repo' format"));
        }
        if (!isValidSha(sha)) {
            return ResponseEntity.badRequest().body(Map.of("error", "sha is not a valid commit SHA"));
        }

        // question/chatHistory are validated on the record. chatHistory in
        // particular is replayed verbatim into an LLM prompt, so it is now
        // bounded in both message count and per-message length — previously an
        // unchecked cast of whatever JSON arrived, which also meant a
        // ClassCastException (500) if it wasn't a list of objects.
        String question = body.question();
        List<Map<String, String>> chatHistory = body.chatHistoryOrEmpty().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

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

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);

            @SuppressWarnings("unchecked")
            Map<String, Object> agentResponse =
                    restTemplate.postForObject(COMMIT_ANALYST_URL, new HttpEntity<>(agentPayload, headers), Map.class);

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
