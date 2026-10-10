package dev.tenantguard.shapes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.Finding;
import dev.tenantguard.Finding.Rule;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.SelfCheck;
import dev.tenantguard.TableDiscovery;
import dev.tenantguard.junit.ObserverSetup;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Where the tables live: a non-public current schema, a shadowed name, schemas off the search_path. */
class SchemaShapesTest extends ShapesTestBase {

    @FunctionalInterface
    interface OwnerWork {
        void run(Connection owner, Statement ddl) throws SQLException;
    }

    @Test
    void discoverySearchesEverySchemaOnTheSearchPath(ObserverSetup setup) {
        assertThat(setup.installedTables()).containsExactly("MixedCase", "order", "public.t_in_schema",
                "t_bigint", "t_composite", "t_in_schema", "t_int", "t_text", "t_uuid");
        assertThat(setup.skippedTables()).containsOnlyKeys("odd name");
        assertThat(setup.skippedTables().get("odd name")).contains("unsupported identifier");
    }

    @Test
    void explicitSchemasLimitDiscoveryAndReachOffThePath() throws SQLException {
        try (Connection owner = ownerOnAppPath()) {
            assertThat(TableDiscovery.discover(owner, "tenant_id", List.of("tenancy")).tables())
                    .containsExactly("t_in_schema");
            assertThat(TableDiscovery.discover(owner, "tenant_id", List.of("archive")).tables())
                    .containsExactly("archive.t_archived");
            assertThatThrownBy(() -> TableDiscovery.discover(owner, "tenant_id", List.of("bad\"schema")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void probeAndObserverFunctionLiveInTheInstallingConnectionsCurrentSchema() throws SQLException {
        try (Connection owner = ownerConnection(); Statement query = owner.createStatement();
             ResultSet rs = query.executeQuery("""
                     SELECT (SELECT string_agg(n.nspname, ',') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE c.relname = 'tg_probe'),
                            (SELECT string_agg(n.nspname, ',') FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                             WHERE p.proname = 'tg_observe')""")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("tenancy");
            assertThat(rs.getString(2)).isEqualTo("tenancy");
        }
    }

    @Test
    void sameTableNameInTwoSchemasIsObservedUnderTwoLabels() {
        seedVictim(IN_SCHEMA);
        seedVictim(SHADOWED);

        guard.runAs("2", () -> {
            unfilteredSum(IN_SCHEMA);
            unfilteredSum(SHADOWED);
        });

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).extracting(Finding::table)
                .containsExactly("t_in_schema", "public.t_in_schema");
    }

    @Test
    void selfCheckFindsTheProbeEvenWhenItIsNotOnTheConnectionsSearchPath() throws SQLException {
        DataSource publicOnly = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (Statement setup = connection.createStatement()) {
                    setup.execute("SET search_path TO public");
                }
                return connection;
            }
        };

        SelfCheck.verify(publicOnly, guard);

        assertThat(guard.observations())
                .anySatisfy(o -> assertThat(o.tenantsByTable()).containsKey(ObserverInstaller.PROBE_TABLE));
    }

    @Test
    void installGrantsOnSchemasThatHoldObservedTablesAndOnTheSearchPathOnly() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE SCHEMA sidecar");
            ddl.execute("CREATE TABLE sidecar.t_side (tenant_id int NOT NULL)");
            ddl.execute("CREATE SCHEMA unrelated");
            ddl.execute("CREATE TABLE unrelated.t_other (tenant_id int NOT NULL)");

            ObserverInstaller.install(owner, "tenant_id", List.of("sidecar.t_side"));

            try (ResultSet rs = ddl.executeQuery("""
                    SELECT has_schema_privilege('tg_app', 'sidecar', 'USAGE'),
                           has_table_privilege('tg_app', 'sidecar.t_side', 'SELECT'),
                           has_schema_privilege('tg_app', 'unrelated', 'USAGE'),
                           has_schema_privilege('tg_app', 'tenancy', 'USAGE'),
                           has_schema_privilege('tg_app', 'public', 'USAGE'),
                           (SELECT qual FROM pg_policies WHERE schemaname = 'sidecar' AND tablename = 't_side'
                              AND policyname = 'tg_observer')""")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("usage on the observed table's schema").isTrue();
                assertThat(rs.getBoolean(2)).as("select on the observed table").isTrue();
                assertThat(rs.getBoolean(3)).as("nothing granted where nothing is observed or on the path").isFalse();
                assertThat(rs.getBoolean(4)).as("usage on the current schema (probe, tg_observe)").isTrue();
                assertThat(rs.getBoolean(5)).as("usage on the rest of the search_path").isTrue();
                assertThat(rs.getString(6)).contains("'sidecar.t_side'");
            }
        });
    }

    @Test
    void installRefusesUnknownAndMalformedTables() throws SQLException {
        rolledBack((owner, ddl) -> {
            assertThatThrownBy(() -> ObserverInstaller.install(owner, "tenant_id", List.of("no_such_table")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no_such_table")
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42P01"));
            assertThatThrownBy(() -> ObserverInstaller.install(owner, "tenant_id", List.of("a.b.c")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ObserverInstaller.install(owner, "tenant_id", List.of("t_int; DROP TABLE t_int")))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    private static Connection ownerOnAppPath() throws SQLException {
        Connection owner = ownerConnection();
        try (Statement setup = owner.createStatement()) {
            setup.execute("SET search_path TO " + SEARCH_PATH);
        }
        return owner;
    }

    private static void rolledBack(OwnerWork work) throws SQLException {
        try (Connection owner = ownerOnAppPath()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                work.run(owner, ddl);
            } finally {
                owner.rollback();
            }
        }
    }
}
