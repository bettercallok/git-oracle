package ai.gitoracle.ingestor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "ai.gitoracle.core.model.postgres")
@EnableJpaRepositories(basePackages = "ai.gitoracle.core.model.postgres")
public class ErrorIngestorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErrorIngestorApplication.class, args);
    }
}
