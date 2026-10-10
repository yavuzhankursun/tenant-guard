package dev.tenantguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.sample.pool.PoolSelectingDataSourceConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * A full-suite run with {@code -Dtenantguard.pool=...} only proves anything if the property really
 * reached the context and the post-processor wrapped that pool. tomcat-jdbc's {@code unwrap} returns
 * null and {@code isWrapperFor} false, so the delegate is read directly.
 */
class PoolSelectionTest extends GuardedTestBase {

    @Autowired
    Environment env;

    @Test
    void theAppDataSourceIsTheSelectedPoolBehindTheGuard() throws ReflectiveOperationException {
        Field delegate = GuardedDataSource.class.getDeclaredField("delegate");
        delegate.setAccessible(true);

        assertThat(dataSource).isInstanceOf(GuardedDataSource.class);
        assertThat(delegate.get(dataSource)).isInstanceOf(PoolSelectingDataSourceConfig.selected(env).type());
        System.out.printf("[pool-selection] pool=%s cache=%s delegate=%s%n", PoolSelectingDataSourceConfig.selected(env),
                PoolSelectingDataSourceConfig.cacheEnabled(env), delegate.get(dataSource).getClass().getName());
    }
}
