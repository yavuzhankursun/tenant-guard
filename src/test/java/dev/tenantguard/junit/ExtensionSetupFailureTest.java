package dev.tenantguard.junit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.TenantGuard;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Field run: setup failures did not say what to configure. Each failure names the thing to check.
 * Drives the extension's setup steps directly, without a Spring Boot context.
 */
class ExtensionSetupFailureTest {

    private static DataSource connectingAs(String user, String password) {
        return new DriverManagerDataSource(GuardedTestBase.POSTGRES.getJdbcUrl(), user, password);
    }

    private static DataSource superuser() {
        return connectingAs(GuardedTestBase.POSTGRES.getUsername(), GuardedTestBase.POSTGRES.getPassword());
    }

    private static TenantGuard activeGuard() {
        TenantGuard guard = new TenantGuard();
        guard.activate();
        return guard;
    }

    @Test
    @DisplayName("DataSource sarılmamış: bean adı, sınıfı, TenantGuardConfiguration ve 'hangi bean oluşturdu'")
    void unguardedDataSourceSaysWhatToImport() {
        try (GenericApplicationContext spring = new GenericApplicationContext()) {
            spring.registerBean("appDataSource", DataSource.class, SimpleDriverDataSource::new);
            spring.refresh();

            assertThatThrownBy(() -> TenantGuardExtension.guardedDataSource(spring))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DataSource bean 'appDataSource' (SimpleDriverDataSource) is not a GuardedDataSource")
                    .hasMessageContaining("import TenantGuardConfiguration")
                    .hasMessageContaining("which bean created the DataSource");
        }
    }

    @Test
    @DisplayName("DataSource bean'i yok: spring.datasource.* ve env anahtarlarını söyler")
    void missingDataSourceSaysWhereItComesFrom() {
        try (GenericApplicationContext spring = new GenericApplicationContext()) {
            spring.refresh();

            assertThatThrownBy(() -> TenantGuardExtension.guardedDataSource(spring))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no DataSource bean")
                    .hasMessageContaining("spring.datasource.url");
        }
    }

    @Test
    @DisplayName("Self-check kör (superuser): mevcut mesaj aynen geçer")
    void blindSelfCheckKeepsItsMessage() {
        DataSource asSuperuser = new GuardedDataSource(superuser(), activeGuard(), GuardedTestBase.POSTGRES.getUsername());

        assertThatThrownBy(() -> TenantGuardExtension.selfCheck(asSuperuser, activeGuard()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TenantGuard is blind: role '%s' bypasses row level security (superuser=true"
                        .formatted(GuardedTestBase.POSTGRES.getUsername()));
    }

    @Test
    @DisplayName("Self-check SQL hatası (rol yok): observer kurulumu ve SET ROLE'u kontrol et der")
    void selfCheckSqlFailureSaysWhatToCheck() {
        TenantGuard guard = activeGuard();
        DataSource missingRole = new GuardedDataSource(superuser(), guard, "tg_no_such_role");

        assertThatThrownBy(() -> TenantGuardExtension.selfCheck(missingRole, guard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantGuard self-check could not run")
                .hasMessageContaining("tg_no_such_role")
                .hasMessageContaining("SET ROLE");
    }

    @Test
    @DisplayName("Tabloların sahibi olmayan kullanıcı: 'uygulama kullanıcısı tabloların sahibi olmalı, şema Flyway ile'")
    void nonOwnerCannotInstallAndIsToldWhy() throws Exception {
        try (Connection owner = GuardedTestBase.ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tg_stranger') THEN
                        CREATE ROLE tg_stranger LOGIN PASSWORD 'stranger';
                      END IF;
                    END $$""");
        }

        assertThatThrownBy(() -> TenantGuardExtension.install(connectingAs("tg_stranger", "stranger"), "tenant_id",
                new String[] {"invoice"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not install the observer as user 'tg_stranger'")
                .hasMessageContaining("42501")
                .hasMessageContaining("must own the tenant tables")
                .hasMessageContaining("Flyway");
    }
}
