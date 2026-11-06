package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.service.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Backs the System status page. Under /api/v1/admin, so platform:admin is required
 * at the gateway and again in this service's TenantContextFilter. Read-only on
 * purpose: an editable LLM URL would let one stolen admin session send every
 * tenant's code to another server.
 */
// No @CrossOrigin: the API Gateway sets the CORS headers (see DashboardController).
@RestController
@RequestMapping("/api/v1/admin/system")
public class SystemStatusController {

    private final SystemStatusService systemStatus;

    public SystemStatusController(SystemStatusService systemStatus) {
        this.systemStatus = systemStatus;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return systemStatus.status();
    }
}
