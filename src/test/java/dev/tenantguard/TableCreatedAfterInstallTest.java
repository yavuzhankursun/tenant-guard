package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A table the owner creates after the observer was installed (a test's own fixture, a lazy
 * migration) must stay usable by the observed role; it is simply not observed, having no policy.
 */
class TableCreatedAfterInstallTest extends GuardedTestBase {

    @AfterEach
    void dropLateTable() throws SQLException {
        try (Connection owner = ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("DROP TABLE IF EXISTS tg_late");
        }
    }

    @Test
    @DisplayName("Kurulumdan sonra oluşturulan tablo: guard aktifken okunur/yazılır, gözlenmez")
    void aTableCreatedAfterInstallIsUsableButNotObserved() throws SQLException {
        try (Connection owner = ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE TABLE tg_late (id bigserial PRIMARY KEY, tenant_id text NOT NULL)");
            ddl.execute("INSERT INTO tg_late (tenant_id) VALUES ('A')");
        }

        Long rows = guard.callAs(TENANT_B, () -> {
            jdbc.update("INSERT INTO tg_late (tenant_id) VALUES ('B')");
            jdbc.update("UPDATE tg_late SET tenant_id = tenant_id");
            return jdbc.queryForObject("SELECT count(*) FROM tg_late", Long.class);
        });

        assertThat(rows).isEqualTo(2);
        assertThat(guard.observations()).as("no policy on the late table, so nothing is observed").isEmpty();
        assertThat(guard.findings()).isEmpty();
    }
}
