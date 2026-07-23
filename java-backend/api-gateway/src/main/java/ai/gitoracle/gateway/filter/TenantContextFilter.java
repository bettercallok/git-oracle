package ai.gitoracle.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
        
        if (tenantId == null || tenantId.trim().isEmpty()) {
            logger.warn("Request rejected: Missing X-Tenant-ID header");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        logger.info("Validated request for Tenant: {}", tenantId);
        
        // Add validated tenant ID to the downstream request context
        ServerWebExchange modifiedExchange = exchange.mutate()
            .request(r -> r.header("X-Tenant-ID", tenantId))
            .build();
            
        return chain.filter(modifiedExchange);
    }

    @Override
    public int getOrder() {
        return -1; // Run before other routing filters
    }
}
