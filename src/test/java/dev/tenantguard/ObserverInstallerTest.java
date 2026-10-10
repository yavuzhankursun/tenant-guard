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
