package dev.tenantguard.shapes;

import dev.tenantguard.Extremes;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.junit.TenantIsolationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Tenant column shapes and table locations, in a schema of their own ({@code db/shapes}) and a
 * container of their own: the shapes carry {@code tenant_id} and would change what auto-discovery
 * finds for every other test in the shared container.
 *
 * <p>The app's connections run with {@code search_path = tenancy, public}, set per connection by
 * Hikari, so the current schema is {@code tenancy} and the shapes in {@code public} are reached
 * through the second path entry. {@code public.t_in_schema} is shadowed by {@code tenancy.t_in_schema}.
 *
 * <p>Every test starts with the attacker's rows only (two per shape); {@link #seedVictim} adds the
 * victim's as the owner, with {@link Extremes} amounts so the differential probe sees aggregates move.
 */
@SpringBootTest(classes = ShapesTestBase.ShapesApp.class)
@TenantIsolationTest(tenantColumn = "tenant_id")
abstract class ShapesTestBase {

    /** Minimal app: DataSource, Flyway and JdbcTemplate from auto-configuration, nothing scanned. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ShapesApp {
    }

    static final String SEARCH_PATH = "tenancy, public";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SEARCH_PATH);
        registry.add("spring.flyway.locations", () -> "classpath:db/shapes");
        registry.add("spring.flyway.default-schema", () -> "public");
    }

    /**
     * One tenant column shape.
     *
     * @param label    what the observer reports as the table: the name as written from the app's
     *                 search_path, schema-qualified only when the bare name resolves to another table
     * @param sql      how test SQL refers to it (quoted where needed)
     * @param column   tenant column
     * @param type     tenant column type, used to cast bind parameters
     * @param victim   canonical {@code ::text} form of the victim's tenant id
     * @param attacker canonical {@code ::text} form of the attacker's tenant id
     */
    record Shape(String label, String sql, String column, String type, String victim, String attacker) {

        @Override
        public String toString() {
            return label + " (" + column + " " + type + ")";
        }
    }

    static final String UUID_A = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String UUID_B = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    static final Shape INT = new Shape("t_int", "t_int", "tenant_id", "integer", "1", "2");
    static final Shape BIGINT = new Shape("t_bigint", "t_bigint", "tenant_id", "bigint", "9000000001", "9000000002");
    static final Shape UUID = new Shape("t_uuid", "t_uuid", "tenant_id", "uuid", UUID_A, UUID_B);
    static final Shape TEXT = new Shape("t_text", "t_text", "tenant_id", "text", "A", "B");
    static final Shape COMPOSITE = new Shape("t_composite", "t_composite", "tenant_id", "integer", "1", "2");
    static final Shape MIXED_CASE = new Shape("MixedCase", "\"MixedCase\"", "tenant_id", "text", "A", "B");
    static final Shape RESERVED = new Shape("order", "\"order\"", "tenant_id", "text", "A", "B");
    static final Shape IN_SCHEMA = new Shape("t_in_schema", "t_in_schema", "tenant_id", "integer", "1", "2");
    static final Shape SHADOWED = new Shape("public.t_in_schema", "public.t_in_schema", "tenant_id", "integer", "1", "2");
    static final Shape ARCHIVED = new Shape("archive.t_archived", "archive.t_archived", "tenant_id", "integer", "1", "2");
    static final Shape COMPANY = new Shape("t_company", "t_company", "company_id", "bigint", "1", "2");

    static final List<Shape> TENANT_ID_ON_PATH =
            List.of(INT, BIGINT, UUID, TEXT, COMPOSITE, MIXED_CASE, RESERVED, IN_SCHEMA, SHADOWED);
    private static final List<Shape> ALL = Stream.concat(TENANT_ID_ON_PATH.stream(), Stream.of(ARCHIVED, COMPANY)).toList();

    static Stream<Shape> tenantIdShapes() {
        return TENANT_ID_ON_PATH.stream();
    }

    @Autowired
    TenantGuard guard;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    /** The extension armed the guard already: seed as the owner, then re-arm and forget the seeding. */
    @BeforeEach
    void seedAttackersOnly() {
        guard.deactivate();
        ALL.forEach(shape -> {
            jdbc.execute("TRUNCATE " + shape.sql() + " RESTART IDENTITY");
            seed(shape, shape.attacker(), List.of(new BigDecimal("100"), new BigDecimal("200")));
        });
        guard.activate();
        guard.reset();
    }

    void seedVictim(Shape shape) {
        seedVictim(shape, shape.victim());
    }

    /** As the owner; {@code tenantLiteral} may be a non-canonical spelling that Postgres casts. */
    void seedVictim(Shape shape, String tenantLiteral) {
        boolean wasActive = guard.isActive();
        guard.deactivate();
        try {
            seed(shape, tenantLiteral, Extremes.amounts());
        } finally {
            if (wasActive) {
                guard.activate();
            }
        }
    }

    private void seed(Shape shape, String tenant, List<BigDecimal> amounts) {
        String sql = "INSERT INTO %s (%s, amount) VALUES (CAST(? AS %s), ?)".formatted(shape.sql(), shape.column(), shape.type());
        amounts.forEach(amount -> jdbc.update(sql, tenant, amount));
    }

    BigDecimal unfilteredSum(Shape shape) {
        return jdbc.queryForObject("SELECT sum(amount) FROM " + shape.sql(), BigDecimal.class);
    }

    BigDecimal filteredSum(Shape shape, String tenant) {
        return jdbc.queryForObject("SELECT sum(amount) FROM %s WHERE %s = CAST(? AS %s)"
                .formatted(shape.sql(), shape.column(), shape.type()), BigDecimal.class, tenant);
    }

    /** Plain connection without the app's search_path: superuser, owner of everything. */
    static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
