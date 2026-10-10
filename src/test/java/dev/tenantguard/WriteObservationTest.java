package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import dev.tenantguard.Finding.Rule;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The observer's WITH CHECK reports every row a statement writes. WRITE_MISMATCH is the write-side
 * IDOR: the actor inserted a row with a spoofed tenant, or updated (moved or edited) a foreign row.
 */
class WriteObservationTest extends GuardedTestBase {

    private static final String INSERT_A = "INSERT INTO invoice (tenant_id, number, amount) VALUES ('A', 'spoofed', 1)";

    @Test
    @DisplayName("B, gövdede A'nın tenant_id'si ile INSERT yapar: WRITE_MISMATCH")
    void anInsertWithTheVictimsTenantIsAWriteMismatch() {
        guard.runAs(TENANT_B, () -> jdbc.update(INSERT_A));

        assertThat(guard.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(Rule.WRITE_MISMATCH);
            assertThat(f.table()).isEqualTo("invoice");
            assertThat(f.tenants()).containsExactly(TENANT_A);
            assertThat(f.sql()).isEqualTo(INSERT_A);
        });
    }

    @Test
    @DisplayName("ORM tarzı INSERT ... RETURNING id: WRITE_MISMATCH de gelir")
    void anInsertReturningTheGeneratedIdIsAWriteMismatchToo() {
        guard.runAs(TENANT_B, () -> jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO invoice (tenant_id, number, amount) VALUES (?, 'spoofed', 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, TENANT_A);
                return ps.executeUpdate();
            }
        }));

        assertThat(guard.findings(Rule.WRITE_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("B kendi tenant'ı ile INSERT yapar: bulgu yok, yazma gözlenir")
    void anInsertWithTheActorsOwnTenantIsNoFinding() {
        guard.runAs(TENANT_B, () -> jdbc.update(
                "INSERT INTO invoice (tenant_id, number, amount) VALUES ('B', 'B-3', 1)"));

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.observations()).singleElement()
                .satisfies(o -> assertThat(o.writtenTenantsByTable()).containsEntry("invoice", Set.of(TENANT_B)));
    }

    @Test
    @DisplayName("B, A'nın satırını UPDATE eder: okuma (USING) ve yazma (WITH CHECK) ayrı bulgular")
    void anUpdateOfAVictimRowIsAReadAndAWriteFinding() {
        guard.runAs(TENANT_B, () -> jdbc.update("UPDATE invoice SET amount = 0 WHERE id = ?", invoiceOfA));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
        assertThat(guard.findings(Rule.WRITE_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("B kendi satırını A'ya taşır: WRITE_MISMATCH; okunan eski satır B'nin")
    void anUpdateMovingTheActorsRowToTheVictimIsAWriteMismatch() {
        guard.runAs(TENANT_B, () -> jdbc.update("UPDATE invoice SET tenant_id = 'A' WHERE tenant_id = 'B' AND number = 'B-1'"));

        assertThat(guard.findings(Rule.WRITE_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
        // The WHERE reads columns, so the UPDATE needs SELECT rights and Postgres checks that the new
        // row stays visible: USING runs on it too, and reports the moved row as a read of A.
        assertThat(guard.rowReads()).containsOnly(
                entry(new RowRead("invoice", TENANT_B), 1L),
                entry(new RowRead("invoice", TENANT_A), 1L),
                entry(RowRead.write("invoice", TENANT_A), 1L));
    }

    @Test
    @DisplayName("WHERE'siz taşıma: SELECT hakkı gerekmez, yalnızca WRITE_MISMATCH")
    void anUpdateWithoutColumnReadsIsAWriteMismatchOnly() {
        guard.deactivate();
        jdbc.update("DELETE FROM invoice_line");
        jdbc.update("DELETE FROM invoice WHERE tenant_id = 'A'");
        guard.activate();

        guard.runAs(TENANT_B, () -> jdbc.update("UPDATE invoice SET tenant_id = 'A'"));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("only B's rows existed to be read").isEmpty();
        assertThat(guard.findings(Rule.WRITE_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("B, A'nın satırını DELETE eder: yalnızca okuma (USING) görülür")
    void aDeleteOfAVictimRowIsSeenAsAReadOnly() {
        guard.runAs(TENANT_B, () -> jdbc.update("DELETE FROM invoice_line WHERE invoice_id = ?", invoiceOfA));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
        assertThat(guard.findings(Rule.WRITE_MISMATCH)).as("DELETE has no new row to check").isEmpty();
    }

    @Test
    @DisplayName("Aktör yok (pasif mod): yazma gözlenir ama yargılanmaz")
    void aWriteWithoutActorIsNoFinding() {
        jdbc.update(INSERT_A);

        assertThat(guard.findings()).isEmpty();
        assertThat(guard.observations()).singleElement()
                .satisfies(o -> assertThat(o.writtenTenantsByTable()).containsEntry("invoice", Set.of(TENANT_A)));
    }

    @Test
    @DisplayName("Kendi politikası olan tablo (legacy_doc): uygulamanın WITH CHECK'i hâlâ uygulanır, yazma gözlenir")
    void theAppsOwnPolicyStillRejectsForeignWritesAndOwnWritesAreObserved() throws SQLException {
        try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
            sql.execute("TRUNCATE legacy_doc");
            guard.trust(ObserverInstaller.install(owner, "tenant_id", List.of("legacy_doc")));
        }
        guard.reset();

        assertThatThrownBy(() -> guard.runAs(TENANT_B, () -> asAppTenant(TENANT_B,
                "INSERT INTO legacy_doc (tenant_id, title) VALUES ('A', 'forged')")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("new row violates row-level security policy");
        guard.runAs(TENANT_B, () -> asAppTenant(TENANT_B,
                "INSERT INTO legacy_doc (tenant_id, title) VALUES ('B', 'own')"));

        assertThat(legacyTitles()).as("the forged row never landed").containsExactly("own");
        // Postgres checks the permissive (app) WITH CHECK before the restrictive observer's, so the
        // rejected row never reaches the observer: like a rejected read, it did not leak.
        assertThat(guard.findings(Rule.WRITE_MISMATCH)).isEmpty();
        assertThat(guard.observations()).anySatisfy(o ->
                assertThat(o.writtenTenantsByTable()).containsEntry("legacy_doc", Set.of(TENANT_B)));
    }

    private Integer asAppTenant(String tenant, String insert) {
        return jdbc.execute((Connection c) -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET app.tenant = '" + tenant + "'");
                try {
                    return st.executeUpdate(insert);
                } finally {
                    st.execute("RESET app.tenant");
                }
            }
        });
    }

    private static List<String> legacyTitles() throws SQLException {
        try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
             ResultSet rs = sql.executeQuery("SELECT title FROM legacy_doc ORDER BY title")) {
            List<String> titles = new java.util.ArrayList<>();
            while (rs.next()) {
                titles.add(rs.getString(1));
            }
            return titles;
        }
    }
}
