package dev.tenantguard.sample.pool;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.beans.PropertyVetoException;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.vibur.dbcp.ViburDBCPDataSource;

/**
 * The pools the observer is proven against. Each wraps Connection, Statement and ResultSet its own
 * way (proxies, unwrap, statement caches, warning handling on return), and the warning-chain cursor
 * was first verified against Hikari only, so every pool is built here the same way, from one URL.
 */
public enum TestPool {
    HIKARI(HikariDataSource.class),
    TOMCAT(org.apache.tomcat.jdbc.pool.DataSource.class),
    DBCP2(BasicDataSource.class),
    C3P0(ComboPooledDataSource.class),
    VIBUR(ViburDBCPDataSource.class),
    /** No pool: a fresh driver connection per borrow. */
    NONE(DriverManagerDataSource.class);

    /** System property selecting the pool of the Spring-context tests; absent means Boot's default, Hikari. */
    public static final String PROPERTY = "tenantguard.pool";
    /** {@code true} enables the pool's statement cache in the Spring-context tests as well. */
    public static final String CACHE_PROPERTY = "tenantguard.pool.cache";

    private static final int CACHED_STATEMENTS = 50;

    private final Class<? extends DataSource> type;

    TestPool(Class<? extends DataSource> type) {
        this.type = type;
    }

    public Class<? extends DataSource> type() {
        return type;
    }

    /** Hikari has no cache of its own; its variant turns on pgjdbc's server-side prepare instead. */
    public boolean hasStatementCache() {
        return this != NONE;
    }

    public static TestPool of(String value) {
        return value == null || value.isBlank() ? HIKARI : valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    /** Connection settings; {@code maxSize = 1} forces a re-borrow onto the same physical connection. */
    public record Settings(String url, String user, String password, int maxSize, boolean statementCache) {
    }

    public DataSource create(Settings s) {
        return switch (this) {
            case HIKARI -> hikari(s);
            case TOMCAT -> tomcat(s);
            case DBCP2 -> dbcp2(s);
            case C3P0 -> c3p0(s);
            case VIBUR -> vibur(s);
            case NONE -> new DriverManagerDataSource(s.url(), s.user(), s.password());
        };
    }

    /** Pools hold sockets and threads; the driver-manager source holds nothing. */
    public static void close(DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        } else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource tomcat) {
            tomcat.close();
        }
    }

    private static DataSource hikari(Settings s) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(s.url());
        config.setUsername(s.user());
        config.setPassword(s.password());
        config.setMaximumPoolSize(s.maxSize());
        if (s.statementCache()) {
            // cachePrepStmts is the MySQL-style knob Hikari's docs recommend; pgjdbc ignores it. Its real
            // statement cache is server-side prepare, which prepareThreshold=1 turns on from the first execute.
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", String.valueOf(CACHED_STATEMENTS));
            config.addDataSourceProperty("prepareThreshold", "1");
        }
        return new HikariDataSource(config);
    }

    private static DataSource tomcat(Settings s) {
        PoolProperties p = new PoolProperties();
        p.setUrl(s.url());
        p.setDriverClassName("org.postgresql.Driver");
        p.setUsername(s.user());
        p.setPassword(s.password());
        p.setMaxActive(s.maxSize());
        p.setMaxIdle(s.maxSize());
        p.setInitialSize(1);
        p.setMinIdle(1);
        if (s.statementCache()) {
            p.setJdbcInterceptors("StatementCache(prepared=true,callable=true,max=" + CACHED_STATEMENTS + ")");
        }
        return new org.apache.tomcat.jdbc.pool.DataSource(p);
    }

    private static DataSource dbcp2(Settings s) {
        BasicDataSource ds = new BasicDataSource();
        ds.setUrl(s.url());
        ds.setUsername(s.user());
        ds.setPassword(s.password());
        ds.setMaxTotal(s.maxSize());
        ds.setMaxIdle(s.maxSize());
        ds.setPoolPreparedStatements(s.statementCache());
        ds.setMaxOpenPreparedStatements(CACHED_STATEMENTS);
        return ds;
    }

    private static DataSource c3p0(Settings s) {
        ComboPooledDataSource ds = new ComboPooledDataSource();
        try {
            ds.setDriverClass("org.postgresql.Driver");
        } catch (PropertyVetoException e) {
            throw new IllegalStateException(e);
        }
        ds.setJdbcUrl(s.url());
        ds.setUser(s.user());
        ds.setPassword(s.password());
        ds.setMinPoolSize(1);
        ds.setInitialPoolSize(1);
        ds.setMaxPoolSize(s.maxSize());
        ds.setMaxStatements(s.statementCache() ? CACHED_STATEMENTS : 0);
        return ds;
    }

    private static DataSource vibur(Settings s) {
        ViburDBCPDataSource ds = new ViburDBCPDataSource();
        ds.setJdbcUrl(s.url());
        ds.setUsername(s.user());
        ds.setPassword(s.password());
        ds.setPoolInitialSize(1);
        ds.setPoolMaxSize(s.maxSize());
        ds.setStatementCacheMaxSize(s.statementCache() ? CACHED_STATEMENTS : 0);
        ds.start();
        return ds;
    }
}
