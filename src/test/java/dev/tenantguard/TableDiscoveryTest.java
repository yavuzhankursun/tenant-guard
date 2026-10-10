package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Extends the base only so the schema has been migrated; DDL here is rolled back. */
class TableDiscoveryTest extends GuardedTestBase {

    @Test
    void findsTenantTablesAndExplainsWhatItSkips() throws Exception {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("CREATE VIEW invoice_view AS SELECT * FROM invoice");
                ddl.execute("CREATE TABLE \"MixedCase\" (tenant_id text)");
                ddl.execute("CREATE TABLE \"odd name\" (tenant_id text)");
                ddl.execute("CREATE TABLE no_tenant (id int)");
                ddl.execute("CREATE TABLE locked (tenant_id text)");
                ddl.execute("ALTER TABLE locked ENABLE ROW LEVEL SECURITY");
                ddl.execute("CREATE POLICY lock ON locked AS RESTRICTIVE USING (true)");

                TableDiscovery.Result result = TableDiscovery.discover(owner, "tenant_id");

                // legacy_doc has its own permissive policy: observed with a RESTRICTIVE observer, not skipped.
                // Quoted identifiers are observed now; only names outside the allowlist are skipped.
                assertThat(result.tables()).containsExactly("MixedCase", "invoice", "invoice_line", "legacy_doc");
                assertThat(result.skipped()).containsOnlyKeys("odd name", "locked");
                assertThat(result.skipped().get("locked")).contains("none is permissive for role 'tg_app'");
                assertThat(result.skipped().get("odd name")).contains("unsupported identifier");
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    void unknownColumnFindsNothing() throws Exception {
        try (Connection owner = ownerConnection()) {
            TableDiscovery.Result result = TableDiscovery.discover(owner, "no_such_column");

            assertThat(result.tables()).isEmpty();
            assertThat(result.skipped()).isEmpty();
        }
    }
}
