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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication(exclude = {Neo4jReactiveDataAutoConfiguration.class})
@EntityScan(basePackages = {"ai.gitoracle.core.model.postgres", "ai.gitoracle.core.entity"})
@EnableJpaRepositories(basePackages = {"ai.gitoracle.core.model.postgres", "ai.gitoracle.orchestrator"})
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    /**
     * A String-valued consumer factory/listener container factory for topics whose
     * payload structure varies per message (e.g. job.events.plan carries a nested
     * investigation_result object) and is published by the Python agents (aiokafka),
     * not Spring Kafka — so there's no __TypeId__ header for Spring's shared
     * JsonDeserializer to key off. Using the app-wide JsonDeserializer for these
     * caused it to bind to whatever Map<String,?> shape another listener on the
     * same container factory happened to declare, crash-looping on every message
     * (confirmed live: 100% CPU, MismatchedInputException on every poll). Parse
     * the raw JSON manually with ObjectMapper instead of relying on type inference.
     */
    @Bean
    public ConsumerFactory<String, String> stringValueConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> stringValueKafkaListenerContainerFactory(
            ConsumerFactory<String, String> stringValueConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stringValueConsumerFactory);
        return factory;
    }

    @Bean
    public CommandLineRunner initDefaultTenant(EntityManager entityManager, TransactionTemplate transactionTemplate) {
        return args -> {
            transactionTemplate.execute(status -> {
                String sql = "INSERT INTO tenants (id, org_name, github_app_installation_id, created_at) " +
                             "VALUES ('00000000-0000-0000-0000-000000000000', 'Default Tenant', '123456', NOW()) " +
                             "ON CONFLICT (org_name) DO NOTHING";
                entityManager.createNativeQuery(sql).executeUpdate();
                System.out.println("Ensured default tenant exists via native query!");
                return null;
            });
        };
    }
}
