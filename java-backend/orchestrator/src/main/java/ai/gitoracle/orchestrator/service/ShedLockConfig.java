package ai.gitoracle.orchestrator.service;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Lets several orchestrator replicas share the scheduled jobs. Each
 * {@code @SchedulerLock} method takes a row in {@code shedlock} first, so only one
 * instance runs it per interval: without this, every replica escalated the same
 * stalled jobs and asked GitHub about the same PRs.
 *
 * <p>Lock times come from the database clock ({@code usingDbTime}), so replicas
 * with drifting clocks still agree.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS shedlock (
            name       VARCHAR(64)  NOT NULL PRIMARY KEY,
            lock_until TIMESTAMP    NOT NULL,
            locked_at  TIMESTAMP    NOT NULL,
            locked_by  VARCHAR(255) NOT NULL
        )""";

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Not a JPA entity, so ddl-auto never creates it.
        jdbc.execute(CREATE_TABLE);
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbc)
            .usingDbTime()
            .build());
    }
}
