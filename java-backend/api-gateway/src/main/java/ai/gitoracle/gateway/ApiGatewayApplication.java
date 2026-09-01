package ai.gitoracle.gateway;

import ai.gitoracle.gateway.filter.TenantContextFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveDataAutoConfiguration;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/**
 * The gateway now needs a DataSource — it resolves API keys to tenants against
 * the {@code api_keys} table. Only the plain JDBC layer is enabled: JPA and
 * Neo4j auto-configuration stay excluded, because the single indexed row read
 * this service performs does not justify starting a Hibernate SessionFactory in
 * a WebFlux application.
 *
 * <p>{@link JpaRepositoriesAutoConfiguration} has to be excluded explicitly
 * alongside Hibernate's. It was previously inert only because no DataSource
 * existed; the moment one appears it activates — git-oracle-core puts
 * spring-data-jpa on this service's classpath — and then fails the context
 * looking for an {@code entityManagerFactory} that Hibernate's excluded
 * auto-configuration never created.
 */
@SpringBootApplication(exclude = {
    HibernateJpaAutoConfiguration.class,
    JpaRepositoriesAutoConfiguration.class,
    Neo4jDataAutoConfiguration.class,
    Neo4jReactiveDataAutoConfiguration.class
})
public class ApiGatewayApplication {

    private static final Logger logger = LoggerFactory.getLogger(ApiGatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    /**
     * Rate-limit bucket key.
     *
     * <p>This used to read {@code X-Tenant-ID} directly off the inbound request,
     * which meant the limiter could be defeated completely: a caller sending a
     * fresh random tenant ID on every request got a fresh bucket every time, so
     * there was effectively no rate limit at all for anyone who noticed. The
     * header is now written by {@link TenantContextFilter} from the
     * authenticated key and any client-supplied value has already been stripped,
     * so the bucket is genuinely per-tenant.
     *
     * <p>Unauthenticated paths that reach a rate-limited route — webhooks, which
     * are HMAC-authenticated further downstream and carry no tenant — fall back
     * to the source IP rather than sharing one global "anonymous" bucket, which
     * would have let a single noisy sender exhaust the allowance for every
     * webhook from everyone.
     */
    @Bean
    public KeyResolver tenantKeyResolver() {
        return exchange -> {
            String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
            if (tenantId != null && !tenantId.isBlank()) {
                return Mono.just("tenant:" + tenantId);
            }
            var remote = exchange.getRequest().getRemoteAddress();
            String ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * The legacy shared key is a single secret that resolves to the default
     * tenant with full platform administration. It exists only to bootstrap the
     * first real per-tenant key without locking the operator out of their own
     * installation. Leaving it enabled in production reinstates precisely the
     * flaw this change removes, so it announces itself on every boot.
     */
    @Bean
    public CommandLineRunner warnAboutLegacyApiKey(
            @Value("${gitoracle.legacy-api-key-enabled:true}") boolean legacyEnabled,
            @Value("${gitoracle.api-key:}") String legacyKey) {
        return args -> {
            if (legacyEnabled && legacyKey != null && !legacyKey.isBlank()) {
                logger.warn("=====================================================================");
                logger.warn("GITORACLE_API_KEY (legacy shared key) is ENABLED.");
                logger.warn("It grants platform:admin over the DEFAULT tenant and is not");
                logger.warn("per-tenant. Mint a real key with:");
                // Single braces: SLF4J only treats "{}" as a placeholder when
                // arguments are supplied, and doubling them here printed the
                // doubled braces literally.
                logger.warn("  POST /api/v1/admin/api-keys  {\"tenantId\":\"...\",\"name\":\"...\"}");
                logger.warn("then set GITORACLE_LEGACY_API_KEY_ENABLED=false and restart.");
                logger.warn("=====================================================================");
            }
        };
    }
}
