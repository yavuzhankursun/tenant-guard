package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgStatement;

/**
 * Proxy behaviour the unit tests script, checked once against the real chain (Hikari + pgjdbc).
 * Reads all of {@code invoice} as actor B; A owns two rows.
 */
class ObservingProxiesPostgresTest extends GuardedTestBase {

    private static final String SCAN = "SELECT id, number FROM invoice";
    private static final RowRead A_INVOICE = new RowRead("invoice", TENANT_A);

    @Test
    @DisplayName("Proxy'den, ham PgStatement'tan, yine proxy'den execute: ortadaki çalıştırmanın notice'leri kaybolmaz")
    void rawExecuteBetweenTwoProxiedExecutesIsNotLost() throws SQLException {
        asB(() -> {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                drain(s.executeQuery(SCAN));
                drain(s.unwrap(PgStatement.class).executeQuery(SCAN));
                // pgjdbc clears the chain here, before the proxy's post-execute harvest.
                drain(s.executeQuery(SCAN));
            }
        });

        assertThat(guard.rowReads()).containsEntry(A_INVOICE, 6L);
    }

    @Test
    @DisplayName("Cursor (fetchSize=1), yalnızca statement kapatılıyor: sonraki partilerin satırları yine de toplanır")
    void cursorRowsAreRecordedWhenOnlyTheStatementIsClosed() throws SQLException {
        asB(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.setFetchSize(1);
                    // The result set is never closed through the proxy; closing the statement closes it in the driver.
                    assertThat(drain(s.executeQuery(SCAN))).isEqualTo(4);
                } finally {
                    c.rollback();
                    c.setAutoCommit(true);
                }
            }
        });

        assertThat(guard.rowReads()).containsEntry(A_INVOICE, 2L);
    }

    @Test
    @DisplayName("Kapalı statement / ResultSet: tekrar close ve isClosed hata vermez")
    void closedStatementsAndResultSetsStayUsable() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(SCAN);
            rs.close();
            rs.close();
            assertThat(rs.isClosed()).isTrue();
            s.close();
            s.close();
            assertThat(s.isClosed()).isTrue();
        }
    }

    @FunctionalInterface
    interface SqlWork {
        void run() throws SQLException;
    }

    private void asB(SqlWork work) {
        guard.runAs(TENANT_B, () -> {
            try {
                work.run();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static int drain(ResultSet rs) throws SQLException {
        int rows = 0;
        while (rs.next()) {
            rows++;
        }
        return rows;
    }
}
