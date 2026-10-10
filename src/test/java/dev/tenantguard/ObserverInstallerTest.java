package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.ObserverInstaller.PolicyKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Extends the base only so the schema has been migrated; DDL here is rolled back. */
class ObserverInstallerTest extends GuardedTestBase {

    @FunctionalInterface
    interface OwnerWork {
        void run(Connection owner, Statement ddl) throws SQLException;
    }

    @Test
    void tableWithoutForeignPoliciesGetsAPermissiveObserver() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE TABLE tg_fresh (tenant_id text NOT NULL)");

            assertThat(ObserverInstaller.policyKind(owner, "tg_fresh")).isEqualTo(PolicyKind.PERMISSIVE);
            ObserverInstaller.install(owner, "tenant_id", List.of("tg_fresh"));

            try (ResultSet rs = ddl.executeQuery("SELECT c.relrowsecurity, p.permissive, p.roles::text "
                    + "FROM pg_class c JOIN pg_policies p ON p.tablename = c.relname "
                    + "WHERE c.relname = 'tg_fresh' AND p.policyname = 'tg_observer'")) {
                assertThat(rs.next()).as("observer policy created").isTrue();
                assertThat(rs.getBoolean(1)).as("row level security enabled").isTrue();
                assertThat(rs.getString(2)).isEqualTo("PERMISSIVE");
                assertThat(rs.getString(3)).isEqualTo("{tg_app}");
            }
        });
    }

    @Test
    void foreignPermissivePolicyForTheObservedRoleMakesTheObserverRestrictive() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE TABLE tg_own_policy (tenant_id text NOT NULL)");
            ddl.execute("CREATE POLICY app_only ON tg_own_policy TO tg_app USING (true)");

            assertThat(ObserverInstaller.policyKind(owner, "tg_own_policy")).isEqualTo(PolicyKind.RESTRICTIVE);
        });
    }

    @Test
    void foreignPermissivePolicyForAnotherRoleOnlyIsRefused() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE ROLE tg_test_other NOLOGIN");
            ddl.execute("CREATE TABLE tg_other_policy (tenant_id text NOT NULL)");
            ddl.execute("CREATE POLICY other_only ON tg_other_policy TO tg_test_other USING (true)");

            // tg_app gets no permissive policy, so every row is denied and the observer would see nothing.
            assertThatThrownBy(() -> ObserverInstaller.policyKind(owner, "tg_other_policy"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[other_only]");
        });
    }

    @Test
    void everyInstallBakesAFreshRandomNonceIntoBothObserverFunctions() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE TABLE tg_fresh (tenant_id text NOT NULL)");

            ObserverInstaller.Installation first = ObserverInstaller.install(owner, "tenant_id", List.of("tg_fresh"));
            assertThat(first.nonce()).matches("[0-9a-f]{32}");
            assertThat(functionSource(ddl, "tg_observe")).contains("tg:" + first.nonce() + "|r|");
            assertThat(functionSource(ddl, "tg_observe_write")).contains("tg:" + first.nonce() + "|w|");

            ObserverInstaller.Installation second = ObserverInstaller.install(owner, "tenant_id", List.of("tg_fresh"));
            assertThat(second.nonce()).isNotEqualTo(first.nonce());
            assertThat(second.location()).as("same database and schema").isEqualTo(first.location());
            assertThat(functionSource(ddl, "tg_observe")).contains(second.nonce()).doesNotContain(first.nonce());
        });
    }

    @Test
    void permissiveAndRestrictiveObserversBothObserveWrites() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE TABLE tg_fresh (tenant_id text NOT NULL)");
            ddl.execute("CREATE TABLE tg_own_policy (tenant_id text NOT NULL)");
            ddl.execute("CREATE POLICY app_only ON tg_own_policy TO tg_app USING (true)");

            ObserverInstaller.install(owner, "tenant_id", List.of("tg_fresh", "tg_own_policy"));

            try (ResultSet rs = ddl.executeQuery("SELECT tablename, permissive, with_check FROM pg_policies "
                    + "WHERE policyname = 'tg_observer' AND tablename IN ('tg_fresh', 'tg_own_policy') ORDER BY 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo("PERMISSIVE");
                assertThat(rs.getString(3)).contains("tg_observe_write('tg_fresh'", "tenant_id");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo("RESTRICTIVE");
                assertThat(rs.getString(3)).contains("tg_observe_write('tg_own_policy'");
            }
        });
    }

    @Test
    void tablesAndSequencesCreatedLaterAreGrantedToTheObservedRole() throws SQLException {
        rolledBack((owner, ddl) -> {
            ddl.execute("CREATE TABLE tg_fresh (tenant_id text NOT NULL)");
            ObserverInstaller.install(owner, "tenant_id", List.of("tg_fresh"));

            ddl.execute("CREATE TABLE tg_later (id bigserial PRIMARY KEY, tenant_id text NOT NULL)");
            try (ResultSet rs = ddl.executeQuery("SELECT has_table_privilege('tg_app', 'tg_later', "
                    + "'SELECT, INSERT, UPDATE, DELETE'), has_sequence_privilege('tg_app', 'tg_later_id_seq', 'USAGE')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("table privileges by default").isTrue();
                assertThat(rs.getBoolean(2)).as("sequence privileges by default").isTrue();
            }
        });
    }

    private static String functionSource(Statement ddl, String name) throws SQLException {
        try (ResultSet rs = ddl.executeQuery("SELECT prosrc FROM pg_proc WHERE proname = '" + name
                + "' AND pronamespace = current_schema()::regnamespace")) {
            assertThat(rs.next()).as(name + " exists").isTrue();
            String source = rs.getString(1);
            assertThat(rs.next()).as("one " + name).isFalse();
            return source;
        }
    }

    private static void rolledBack(OwnerWork work) throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                work.run(owner, ddl);
            } finally {
                owner.rollback();
            }
        }
    }
}
