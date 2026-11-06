package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.security.RouteAccess;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service's routes that the gateway exposes. The full table of the
 * orchestrator's routes is RouteAccessMatrixTest; this pins the ones here.
 */
class RouteAccessTest {

    @Test
    void routesInThisServiceRequireWhatTheyShould() {
        assertThat(RouteAccess.resolve("POST", "/api/v1/admin/webhook-simulator")).isEqualTo(RouteAccess.Access.ADMIN);
    }
}
