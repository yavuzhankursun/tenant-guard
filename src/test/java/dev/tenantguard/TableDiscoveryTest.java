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
                ddl.execute("CREATE TABLE no_tenant (id int)");

                TableDiscovery.Result result = TableDiscovery.discover(owner, "tenant_id");

                assertThat(result.tables()).containsExactly("invoice", "invoice_line");
                assertThat(result.skipped()).containsOnlyKeys("MixedCase", "legacy_doc");
                assertThat(result.skipped().get("legacy_doc")).contains("legacy_isolation");
                assertThat(result.skipped().get("MixedCase")).contains("unsupported identifier");
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
