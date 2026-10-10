package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pgjdbc behaviour the observing proxies are built on, checked on the raw driver (no proxy,
 * no Hikari), so a driver upgrade that changes it fails here by name instead of as a short row
 * count somewhere in {@link JdbcPathMatrixTest}. Verified on 42.6.2, 42.7.10 and 42.7.14.
 */
class PgjdbcContractTest {

    /** One NOTICE {@code n|<i>} per row, raised while the row is produced. */
    private static final String NOTICE_FN = """
            CREATE FUNCTION pg_temp.n(i int) RETURNS int LANGUAGE plpgsql AS $$
            BEGIN RAISE NOTICE 'n|%', i; RETURN i; END $$""";
    private static final String THREE_ROWS = "SELECT pg_temp.n(g) FROM generate_series(1, 3) g";

    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        connection = GuardedTestBase.ownerConnection();
        DatabaseMetaData meta = connection.getMetaData();
        System.out.printf("[pgjdbc] driver %s, server %s%n", meta.getDriverVersion(), meta.getDatabaseProductVersion());
        try (Statement s = connection.createStatement()) {
            s.execute("SET client_min_messages = notice");
            s.execute(NOTICE_FN);
        }
    }

    @AfterAll
    static void disconnect() throws SQLException {
        connection.close();
    }

    @Test
    @DisplayName("Every execute replaces the Statement's warning chain without calling clearWarnings()")
    void executeReplacesTheStatementChain() throws SQLException {
        try (Statement s = connection.createStatement()) {
            drain(s.executeQuery("SELECT pg_temp.n(1)"));
            assertThat(messages(s.getWarnings())).containsExactly("n|1");

            drain(s.executeQuery("SELECT pg_temp.n(2)"));
            assertThat(messages(s.getWarnings())).as("previous run's notices are gone").containsExactly("n|2");
        }
    }

    @Test
    @DisplayName("Same for a reused PreparedStatement")
    void preparedExecuteReplacesTheChain() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_temp.n(?)")) {
            for (int i = 1; i <= 2; i++) {
                ps.setInt(1, i);
                drain(ps.executeQuery());
                assertThat(messages(ps.getWarnings())).containsExactly("n|" + i);
            }
        }
    }

    @Test
    @DisplayName("Cursor fetch (fetchSize in a transaction): later batches' notices land on the ResultSet, not the Statement")
    void cursorFetchNoticesLandOnTheResultSet() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement s = connection.createStatement()) {
            s.setFetchSize(1);
            try (ResultSet rs = s.executeQuery(THREE_ROWS)) {
                drain(rs);
                List<String> onStatement = messages(s.getWarnings());
                List<String> onResultSet = messages(rs.getWarnings());

                assertThat(onStatement).as("first batch").containsExactly("n|1");
                assertThat(onResultSet).as("later batches").contains("n|2", "n|3").doesNotContain("n|1");
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("getWarnings() after close throws, on Statement and ResultSet: harvest before delegating close")
    void getWarningsAfterCloseThrows() throws SQLException {
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery("SELECT pg_temp.n(1)");
        rs.close();
        assertThatThrownBy(rs::getWarnings).isInstanceOf(SQLException.class);
        s.close();
        assertThatThrownBy(s::getWarnings).isInstanceOf(SQLException.class);
    }

    private static void drain(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getObject(1);
        }
    }

    private static List<String> messages(SQLWarning head) {
        List<String> messages = new ArrayList<>();
        for (SQLWarning w = head; w != null; w = w.getNextWarning()) {
            messages.add(w.getMessage());
        }
        return messages;
    }
}
