package ai.gitoracle.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveDataAutoConfiguration;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(exclude = {Neo4jReactiveDataAutoConfiguration.class})
@EntityScan(basePackages = "ai.gitoracle.core.model.postgres")
@EnableJpaRepositories(basePackages = {"ai.gitoracle.core.model.postgres", "ai.gitoracle.orchestrator"})
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Bean
    public CommandLineRunner initDefaultTenant(EntityManager entityManager, TransactionTemplate transactionTemplate) {
        return args -> {
            transactionTemplate.execute(status -> {
                String sql = "INSERT INTO tenants (id, org_name, github_app_installation_id, is_active, created_at, updated_at) " +
                             "VALUES ('00000000-0000-0000-0000-000000000000', 'Default Tenant', '123456', true, NOW(), NOW()) " +
                             "ON CONFLICT (org_name) DO NOTHING";
                entityManager.createNativeQuery(sql).executeUpdate();
                System.out.println("Ensured default tenant exists via native query!");
                return null;
            });
        };
    }
}
