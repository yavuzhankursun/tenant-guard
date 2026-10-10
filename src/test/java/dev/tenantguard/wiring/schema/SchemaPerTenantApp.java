package dev.tenantguard.wiring.schema;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Hibernate schema multitenancy: a {@link MultiTenantConnectionProvider} switches each session's
 * connection to the tenant's schema ({@code tenant_a}, {@code tenant_b}). The design Hibernate 5
 * called {@code multiTenancy=SCHEMA}; Hibernate 6 infers it from the provider.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = SchemaInvoice.class)
public class SchemaPerTenantApp {

    public static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> "a");

    @Bean
    HibernatePropertiesCustomizer schemaPerTenant(DataSource dataSource) {
        return properties -> {
            properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, new SchemaSwitching(dataSource));
            properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new CurrentTenant());
        };
    }

    static final class SchemaSwitching implements MultiTenantConnectionProvider<String> {

        private final DataSource dataSource;

        SchemaSwitching(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getAnyConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        public Connection getConnection(String tenant) throws SQLException {
            Connection connection = getAnyConnection();
            connection.setSchema("tenant_" + tenant);
            return connection;
        }

        @Override
        public void releaseConnection(String tenant, Connection connection) throws SQLException {
            connection.setSchema("public");
            releaseAnyConnection(connection);
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> type) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    static final class CurrentTenant implements CurrentTenantIdentifierResolver<String> {

        @Override
        public String resolveCurrentTenantIdentifier() {
            return CURRENT.get();
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }
}
