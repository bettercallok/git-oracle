package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AuditEntry;
import ai.gitoracle.core.model.postgres.OrgInvitation;
import ai.gitoracle.core.model.postgres.OrgMembership;
import ai.gitoracle.core.model.postgres.OrgRole;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.AuditEntryRepository;
import ai.gitoracle.orchestrator.repository.OrgInvitationRepository;
import ai.gitoracle.orchestrator.repository.OrgMembershipRepository;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.security.TenantContext;
import ai.gitoracle.orchestrator.service.OrganizationService;
import ai.gitoracle.orchestrator.service.OrganizationService.Actor;
import ai.gitoracle.orchestrator.service.OrganizationService.OrganizationException;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's active organisation: who's in it and who's invited.
 *
 * <p>There's no route for an organisation to add repositories to its own allow-list
 * yet. Every organisation still shares one GitHub App installation, so that would
 * let any org admin reach any repository the installation can see. It comes with
 * per-organisation GitHub connections; until then a platform admin manages the list.
 */
// No @CrossOrigin: the API Gateway sets the CORS headers (see DashboardController).
@RestController
@RequestMapping("/api/v1/org")
public class OrganizationController {

    private static final int MAX_AUDIT_ENTRIES = 200;

    private final OrganizationService organizations;
    private final OrgMembershipRepository memberships;
    private final OrgInvitationRepository invitations;
    private final UserAccountRepository users;
    private final AuditEntryRepository auditEntries;
    private final EntityManager entityManager;

    public OrganizationController(OrganizationService organizations, OrgMembershipRepository memberships,
                                  OrgInvitationRepository invitations, UserAccountRepository users,
                                  AuditEntryRepository auditEntries, EntityManager entityManager) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.invitations = invitations;
        this.users = users;
        this.auditEntries = auditEntries;
        this.entityManager = entityManager;
    }

    private static Actor actor() {
        return new Actor(TenantContext.userId(), TenantContext.isPlatformAdmin());
    }

    @GetMapping
    public Map<String, Object> organization() {
        UUID tenantId = TenantContext.requireTenantId();
        Tenant tenant = entityManager.find(Tenant.class, tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", tenantId.toString());
        body.put("name", tenant == null ? null : tenant.getOrgName());
        UUID userId = TenantContext.userId();
        body.put("role", userId == null ? null
            : memberships.findByTenantIdAndUserId(tenantId, userId).map(OrgMembership::getRole).orElse(null));
        return body;
    }

    @GetMapping("/members")
    public List<Map<String, Object>> members() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgMembership member : memberships.findByTenantIdOrderByCreatedAt(TenantContext.requireTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", member.getUserId().toString());
            row.put("githubLogin", users.findById(member.getUserId()).map(UserAccount::getGithubLogin).orElse(null));
            row.put("role", member.getRole());
            row.put("joinedAt", member.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    @PutMapping("/members/{userId}/role")
    public ResponseEntity<Void> changeRole(@PathVariable UUID userId, @Valid @RequestBody Requests.ChangeRole request) {
        organizations.changeRole(TenantContext.requireTenantId(), actor(), userId, OrgRole.valueOf(request.role()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID userId) {
        organizations.removeMember(TenantContext.requireTenantId(), actor(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgInvitation invitation : invitations.findByTenantIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                TenantContext.requireTenantId(), OffsetDateTime.now())) {
            out.add(describe(invitation));
        }
        return out;
    }

    @PostMapping("/invitations")
    public Map<String, Object> invite(@Valid @RequestBody Requests.InviteMember request) {
        return describe(organizations.invite(TenantContext.requireTenantId(), actor(), request.githubLogin(), OrgRole.valueOf(request.role())));
    }

    @DeleteMapping("/invitations/{id}")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID id) {
        organizations.revokeInvitation(TenantContext.requireTenantId(), actor(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_AUDIT_ENTRIES));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry entry : auditEntries.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, size))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", entry.getAction());
            row.put("actor", entry.getActorUserId() == null ? null
                : users.findById(entry.getActorUserId()).map(UserAccount::getGithubLogin).orElse(null));
            row.put("target", entry.getTarget());
            row.put("details", entry.getDetails());
            row.put("createdAt", entry.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    static Map<String, Object> describe(OrgInvitation invitation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", invitation.getId().toString());
        row.put("githubLogin", invitation.getGithubLogin());
        row.put("role", invitation.getRole());
        row.put("expiresAt", invitation.getExpiresAt());
        return row;
    }

    @ExceptionHandler(OrganizationException.class)
    public ResponseEntity<Map<String, String>> refused(OrganizationException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
