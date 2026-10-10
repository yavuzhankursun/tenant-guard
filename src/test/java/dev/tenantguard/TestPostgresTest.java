package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TestPostgresTest {

    @Test
    void systemPropertyWinsOverEnvironmentWhichWinsOverDefault() {
        assertThat(TestPostgres.resolveImage("postgres:13-alpine", "postgres:15")).isEqualTo("postgres:13-alpine");
        assertThat(TestPostgres.resolveImage(null, "postgres:15")).isEqualTo("postgres:15");
        assertThat(TestPostgres.resolveImage(" ", "")).isEqualTo(TestPostgres.DEFAULT_IMAGE);
        assertThat(TestPostgres.resolveImage(null, null)).isEqualTo("postgres:16-alpine");
    }

    @Test
    void containersRunTheConfiguredImage() throws SQLException {
        assertThat(GuardedTestBase.POSTGRES.getDockerImageName()).isEqualTo(TestPostgres.image());
        try (Connection c = GuardedTestBase.ownerConnection()) {
            assertThat(TestPostgres.serverVersionNum(c)).isGreaterThanOrEqualTo(130000);
        }
    }
}
