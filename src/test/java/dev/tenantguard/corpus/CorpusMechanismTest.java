package dev.tenantguard.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.Finding.Rule;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.sample.corpus.CorpusCachedReports;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Why the corpus' blind rows are blind. Each test isolates one mechanism, so a README sentence can
 * point at evidence rather than at a guess. DDL here runs on a raw owner connection and is rolled back.
 */
class CorpusMechanismTest extends CorpusTestBase {

    private static final String VIEW_SUM = "SELECT sum(amount) FROM corpus_invoice_view";
    private static final String DEFINER_SUM = "SELECT corpus_total_definer()";

    @Autowired
    CorpusCachedReports cached;

    @Test
    @DisplayName("23/24b: view and SECURITY DEFINER read as their owner, who bypasses RLS; owned by tg_app they are observed")
    void viewAndDefinerFunctionAreBlindBecauseOfTheirOwner() throws Exception {
        seedVictim();
        Map<String, Object> owners = jdbc.queryForMap("""
                SELECT v.relowner = t.relowner AS view_owned_by_table_owner,
                       p.proowner = t.relowner AS function_owned_by_table_owner,
                       p.prosecdef AS security_definer,
                       t.relrowsecurity AS rls_enabled,
                       t.relforcerowsecurity AS rls_forced
                FROM pg_class t, pg_class v, pg_proc p
                WHERE t.relname = 'corpus_invoice' AND v.relname = 'corpus_invoice_view'
                  AND p.proname = 'corpus_total_definer'""");
        // Without FORCE ROW LEVEL SECURITY a table's owner is exempt from its policies (and here the
        // owner is also Testcontainers' superuser, exempt even with FORCE).
        assertThat(owners).containsEntry("view_owned_by_table_owner", true)
                .containsEntry("function_owned_by_table_owner", true)
                .containsEntry("security_definer", true)
                .containsEntry("rls_enabled", true)
                .containsEntry("rls_forced", false);

        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try (Statement sql = owner.createStatement()) {
                asObservedRole(sql);
                assertThat(observedTenants(sql, VIEW_SUM)).as("view owned by the table owner").isEmpty();
                assertThat(observedTenants(sql, DEFINER_SUM)).as("definer function owned by the table owner").isEmpty();

                sql.execute("RESET ROLE");
                sql.execute("ALTER VIEW corpus_invoice_view OWNER TO " + ObserverInstaller.ROLE);
                sql.execute("ALTER FUNCTION corpus_total_definer() OWNER TO " + ObserverInstaller.ROLE);
                asObservedRole(sql);
                assertThat(observedTenants(sql, VIEW_SUM)).as("same view owned by tg_app").contains(VICTIM, ATTACKER);
                assertThat(observedTenants(sql, DEFINER_SUM)).as("same function owned by tg_app").contains(VICTIM, ATTACKER);
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("15 vs 14: texteq is leakproof and runs before the observer policy; lower() is not and runs after it")
    void leakproofPredicateRunsBeforeThePolicy() {
        Map<String, Object> leakproof = jdbc.queryForMap("""
                SELECT (SELECT proleakproof FROM pg_proc WHERE proname = 'texteq') AS texteq,
                       (SELECT bool_or(proleakproof) FROM pg_proc WHERE proname = 'lower') AS lower""");
        assertThat(leakproof).containsEntry("texteq", true).containsEntry("lower", false);

        // EXPLAIN as tg_app (guard active) shows the RLS-aware qual order without executing the policy.
        String equal = filterOf("SELECT 1 FROM corpus_invoice WHERE name = 'B-3'");
        String lowered = filterOf("SELECT 1 FROM corpus_invoice WHERE lower(name) = 'b-3'");
        System.out.printf("[corpus] filter name =   : %s%n[corpus] filter lower() = : %s%n", equal, lowered);

        assertThat(equal.indexOf("name = ")).as(equal).isLessThan(equal.indexOf("tg_observe"));
        assertThat(lowered.indexOf("tg_observe")).as(lowered).isLessThan(lowered.indexOf("lower("));
    }

    @Test
    @DisplayName("18: @Cacheable is observed on the cold call only; every later call is served from memory")
    void cacheIsObservedOnTheColdCallOnly() {
        seedVictim();

        guard.runAs(ATTACKER, cached::cachedTotal);
        assertThat(guard.findings(Rule.ACTOR_MISMATCH)).as("cold call reads the table").isNotEmpty();

        guard.reset();
        guard.runAs(ATTACKER, cached::cachedTotal);
        assertThat(guard.observations()).as("warm call never reaches Postgres").isEmpty();
    }

    private String filterOf(String query) {
        return jdbc.queryForList("EXPLAIN (COSTS OFF) " + query, String.class).stream()
                .filter(line -> line.contains("Filter:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Filter line in the plan of " + query));
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final Pattern CORPUS_READ = Pattern.compile("tg:[0-9a-f]{32}\\|r\\|corpus_invoice\\|(.*)");

    private static void asObservedRole(Statement sql) throws SQLException {
        sql.execute("SET ROLE " + ObserverInstaller.ROLE);
        sql.execute("SET client_min_messages = notice");
    }

    /** Tenants in this statement's observer notices; a raw connection, so we read the chain ourselves. */
    private static List<String> observedTenants(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                rs.getObject(1);
            }
        }
        List<String> tenants = new ArrayList<>();
        for (SQLWarning w = sql.getWarnings(); w != null; w = w.getNextWarning()) {
            // Any nonce: this raw connection bypasses the guard that knows the install's nonce.
            Matcher read = CORPUS_READ.matcher(String.valueOf(w.getMessage()));
            if (read.matches()) {
                tenants.add(read.group(1).strip());
            }
        }
        sql.clearWarnings();
        return tenants;
    }
}
