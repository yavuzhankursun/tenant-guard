package dev.tenantguard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one place the test tree picks its Postgres image: system property {@value #PROPERTY}, then
 * environment variable {@value #ENV}, then {@value #DEFAULT_IMAGE}. Every container is built here,
 * so {@code mvn test -Dtenantguard.pg.image=postgres:13-alpine} runs the whole suite on 13.
 */
public final class TestPostgres {

    public static final String PROPERTY = "tenantguard.pg.image";
    public static final String ENV = "TENANTGUARD_PG_IMAGE";
    public static final String DEFAULT_IMAGE = "postgres:16-alpine";

    private TestPostgres() {
    }

    public static String image() {
        return resolveImage(System.getProperty(PROPERTY), System.getenv(ENV));
    }

    static String resolveImage(String property, String env) {
        if (property != null && !property.isBlank()) {
            return property.strip();
        }
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        return DEFAULT_IMAGE;
    }

    /** Not started. Custom images (e.g. PostGIS, a private mirror) are accepted as Postgres substitutes. */
    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(DockerImageName.parse(image()).asCompatibleSubstituteFor("postgres"));
    }

    /** {@code server_version_num}, e.g. {@code 150004}; compare with {@code 150000} for "15 or newer". */
    public static int serverVersionNum(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_setting('server_version_num')::int")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
