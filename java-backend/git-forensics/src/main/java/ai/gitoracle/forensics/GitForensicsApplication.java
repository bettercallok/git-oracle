package ai.gitoracle.forensics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = "ai.gitoracle.core.model.neo4j")
public class GitForensicsApplication {
    public static void main(String[] args) {
        SpringApplication.run(GitForensicsApplication.class, args);
    }
}
