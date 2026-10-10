package dev.tenantguard.wiring;

import dev.tenantguard.TenantSeeder;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An app with a {@code @Primary} DataSource and a {@code reporting} one (a read replica or a
 * reporting database). Two databases with the same invoice table; Flyway migrates the primary, the
 * reporting schema is created here. The seeder writes both, as the owner.
 */
@JdbcApp
class TwoDataSourcesApp {

    static final String MAIN = WiringDb.create("wiring_two_main");
    static final String REPORTING = WiringDb.create("wiring_two_reporting");

    static {
        WiringDb.admin("wiring_two_reporting", WiringDb.INVOICE_DDL);
    }

    @Bean
    @Primary
    DataSource dataSource() {
        return WiringDb.pool(MAIN);
    }

    @Bean
    DataSource reporting() {
        return WiringDb.pool(REPORTING);
    }

    @Bean
    TenantSeeder seeder(JdbcTemplate jdbc, @Qualifier("reporting") DataSource reporting) {
        TenantSeeder main = WiringDb.seeder(jdbc);
        TenantSeeder replica = WiringDb.seeder(new JdbcTemplate(reporting));
        return tenant -> {
            main.seed(tenant);
            replica.seed(tenant);
        };
    }
}
