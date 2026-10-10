package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.zaxxer.hikari.HikariDataSource;
import dev.tenantguard.Finding;
import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.RowRead;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared fixture for the wiring tests. Each test class gets its own database in the scenario tests'
 * container: wirings differ in schema, owner and search_path, and a tenant table in the shared
 * {@code test} database would change what auto-discovery finds for every other test. Roles are
 * cluster-wide, so {@code tg_app} and the login roles below exist once for all databases.
 */
final class WiringDb {

    static final PostgreSQLContainer<?> POSTGRES = GuardedTestBase.POSTGRES;
    static final String ADMIN = POSTGRES.getUsername();
    static final String ADMIN_PASSWORD = POSTGRES.getPassword();

    /** Same shape as {@code db/migration/V1__schema.sql}'s invoice, for databases Flyway does not migrate. */
    static final String INVOICE_DDL = """
            CREATE TABLE invoice (
                id        bigserial PRIMARY KEY,
                tenant_id text    NOT NULL,
                number    text    NOT NULL,
                amount    numeric NOT NULL
            )""";

    private WiringDb() {
    }

    /** Drops and recreates {@code name}, owned by the container's superuser. */
    static String create(String name) {
        return create(name, ADMIN);
    }

    /** Drops and recreates {@code name} owned by {@code owner}: {@code owner} may then create in its public schema. */
    static String create(String name, String owner) {
        admin("postgres", "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)",
                "CREATE DATABASE " + name + " OWNER " + owner);
        return url(name);
    }

    static String url(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
    }

    static HikariDataSource pool(String url) {
        return pool(url, ADMIN, ADMIN_PASSWORD);
    }

    /**
     * A pool the app builds itself, outside Boot's auto-configuration. Small: the container is shared
     * and cached test contexts keep their pools open until the JVM exits.
     */
    static HikariDataSource pool(String url, String user, String password) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(1);
        return pool;
    }

    /**
     * Runs a fixture test class (a static nested class, which Surefire never selects by itself) and
     * returns its test events, so a test can assert that the fixture's setup failed and how.
     */
    static Events runFixture(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute().testEvents();
    }

    /** Runs statements as the container's superuser in {@code database}. */
    static void admin(String database, String... sql) {
        execute(url(database), ADMIN, ADMIN_PASSWORD, sql);
    }

    static void execute(String url, String user, String password, String... sql) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            for (String each : sql) {
                statement.execute(each);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture SQL failed: " + e.getMessage(), e);
        }
    }

    /** Creates a login role once per cluster; {@code attributes} e.g. {@code NOSUPERUSER NOBYPASSRLS}. */
    static void loginRole(String role, String password, String attributes) {
        admin("postgres", """
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                    CREATE ROLE %1$s LOGIN PASSWORD '%2$s' %3$s;
                  END IF;
                END $$""".formatted(role, password, attributes));
    }

    /** The observed role as the installer creates it, for fixtures that grant it before any install ran. */
    static void observedRole() {
        admin("postgres", """
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tg_app') THEN
                    CREATE ROLE tg_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
                  END IF;
                END $$""");
    }

    /** Victim A first (and truncates), then attacker B: two invoices each, written with the guard off. */
    static TenantSeeder seeder(JdbcTemplate jdbc) {
        return tenant -> {
            if ("A".equals(tenant)) {
                jdbc.execute("TRUNCATE invoice RESTART IDENTITY CASCADE");
            }
            for (int i = 1; i <= 2; i++) {
                jdbc.update("INSERT INTO invoice (tenant_id, number, amount) VALUES (?, ?, ?)",
                        tenant, tenant + "-" + i, 100 * i);
            }
        };
    }

    /** Tenant B's unfiltered read of {@code invoice}: A's rows reported, each exactly once per read. */
    static void assertLeakOfA(TenantGuard guard, long rowsOfARead) {
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH))
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.table()).isEqualTo("invoice");
                    assertThat(f.tenants()).containsExactly("A");
                });
        assertThat(guard.rowReads()).as("each row read reported once, not once per wrapper")
                .containsEntry(new RowRead("invoice", "A"), rowsOfARead);
    }

    /** One unfiltered statement over A's two rows. */
    static void assertLeakOfA(TenantGuard guard) {
        assertThat(guard.findings(Finding.Rule.ACTOR_MISMATCH)).hasSize(1);
        assertLeakOfA(guard, 2);
    }
}
