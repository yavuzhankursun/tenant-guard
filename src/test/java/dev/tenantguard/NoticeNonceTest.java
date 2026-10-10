package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.Finding.Rule;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A NOTICE is only an observation when it carries the nonce of the install that wrote the observer
 * function. Anything else that reaches the warning chain, the app's own {@code RAISE NOTICE} or a
 * tenant-controlled string the app raises, must be invisible to the guard.
 */
class NoticeNonceTest extends GuardedTestBase {

    private static final String FOREIGN_NONCE = "0".repeat(32);

    @Test
    @DisplayName("Uygulamanın kendi RAISE NOTICE 'tg|invoice|A' mesajı gözlem sayılmaz")
    void anAppRaisedUnsignedNoticeIsNotAnObservation() {
        guard.runAs(TENANT_B, () -> jdbc.execute("DO $$ BEGIN RAISE NOTICE 'tg|invoice|A'; END $$"));

        assertThat(guard.observations()).isEmpty();
        assertThat(guard.findings()).isEmpty();
    }

    @Test
    @DisplayName("Yanlış nonce'lu notice gözlem sayılmaz; aynı ifadedeki gerçek gözlem sayılır")
    void aNoticeWithAnotherNonceIsNotAnObservationButTheRealOneIs() {
        String sql = """
                DO $$ BEGIN
                  RAISE NOTICE 'tg:%s|r|invoice|A';
                  RAISE NOTICE 'tg:%s|w|invoice|A';
                  RAISE NOTICE 'tg|invoice|A';
                  PERFORM count(*) FROM invoice WHERE tenant_id = 'B';
                END $$""".formatted(FOREIGN_NONCE, FOREIGN_NONCE);

        guard.runAs(TENANT_B, () -> jdbc.execute(sql));

        assertThat(guard.findings()).as("every forged A row was dropped").isEmpty();
        assertThat(guard.observations()).as("the observer's own notices in the same statement still count")
                .singleElement().satisfies(o -> {
                    assertThat(o.tenantsByTable()).containsOnlyKeys("invoice");
                    assertThat(o.tenantsByTable().get("invoice")).isEqualTo(Set.of(TENANT_B));
                    assertThat(o.writtenTenantsByTable()).isEmpty();
                });
    }

    @Test
    @DisplayName("Gerçek observer hâlâ gözlenir")
    void theRealObserverIsStillObserved() {
        guard.runAs(TENANT_B, () -> jdbc.queryForObject("SELECT count(*) FROM invoice", Long.class));

        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).singleElement()
                .satisfies(f -> assertThat(f.tenants()).containsExactly(TENANT_A));
    }

    @Test
    @DisplayName("Yeniden kurulum: havuzdaki uzun ömürlü bağlantı yeni nonce ile gözlenmeye devam eder")
    void aReinstallKeepsALongLivedPooledConnectionObserved() throws SQLException {
        try (Connection held = dataSource.getConnection()) {
            guard.runAs(TENANT_B, () -> count(held));
            assertThat(guard.findings(Rule.ACTOR_MISMATCH)).hasSize(1);

            ObserverInstaller.Installation reinstalled;
            try (Connection owner = ownerConnection()) {
                reinstalled = ObserverInstaller.install(owner, "tenant_id", List.of("invoice", "invoice_line"));
            }
            guard.trust(reinstalled);
            guard.reset();

            guard.runAs(TENANT_B, () -> count(held));
            assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("the replaced function raises the new nonce")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("Nonce'a güvenmeyen guard kördür: self-check FAIL verir")
    void aGuardThatDoesNotTrustTheInstallFailsTheSelfCheck() {
        TenantGuard stranger = new TenantGuard();
        stranger.activate();
        DataSource guarded = new GuardedDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
                stranger, ObserverInstaller.ROLE);

        assertThatThrownBy(() -> SelfCheck.verify(guarded, stranger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe query produced no observer notice")
                .hasMessageContaining("TenantGuard.trust");
    }

    @Test
    @DisplayName("Doğru kurulum: self-check geçer")
    void theTrustingGuardPassesTheSelfCheck() throws SQLException {
        SelfCheck.verify(dataSource, guard);
    }

    private static Long count(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM invoice")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
