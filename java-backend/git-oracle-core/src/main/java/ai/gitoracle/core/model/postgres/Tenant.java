package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Data
public class Tenant {

    /**
     * The single-tenant installation's tenant, seeded at orchestrator startup.
     *
     * <p>Previously spelled as a literal zero-UUID string in five places across
     * three services (and once more in the dashboard's axios client), which is
     * how it ended up being used both as a legitimate default for a
     * single-tenant deployment <em>and</em> as a hardcoded override that
     * discarded whatever tenant a request actually belonged to. Naming it makes
     * every remaining use visible and reviewable.
     */
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_name", unique = true, nullable = false)
    private String orgName;

    @Column(name = "github_app_installation_id")
    private String githubAppInstallationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String config = "{}";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
