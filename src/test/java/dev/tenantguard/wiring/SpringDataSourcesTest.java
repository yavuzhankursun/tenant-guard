package dev.tenantguard.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.spring.SpringDataSources;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.util.ReflectionUtils;

/** Where the post-processor puts the guard, without a database: the bean graphs of the wiring tests. */
class SpringDataSourcesTest {

    private final TenantGuard guard = new TenantGuard();
    private final UnaryOperator<DataSource> wrap = target -> new GuardedDataSource(target, guard, ObserverInstaller.ROLE);

    private static DataSource pool() {
        return mock(DataSource.class);
    }

    private static DataSource target(DelegatingDataSource proxy) {
        return proxy.getTargetDataSource();
    }

    @Test
    void aPlainPoolIsWrapped() {
        DataSource pool = pool();

        assertThat(SpringDataSources.guard(pool, wrap)).isInstanceOf(GuardedDataSource.class);
    }

    @Test
    void aGuardIsLeftAlone() {
        DataSource guarded = wrap.apply(pool());

        assertThat(SpringDataSources.guard(guarded, wrap)).isSameAs(guarded);
    }

    @Test
    void springProxiesKeepTheirTypeAndGetTheGuardOnTheirPool() {
        DataSource pool = pool();
        TransactionAwareDataSourceProxy transactionAware = new TransactionAwareDataSourceProxy(pool);
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(transactionAware);

        assertThat(SpringDataSources.guard(lazy, wrap)).isSameAs(lazy);
        assertThat(target(lazy)).isSameAs(transactionAware);
        assertThat(target(transactionAware)).isInstanceOf(GuardedDataSource.class);
    }

    @Test
    void aProxyOverAGuardedPoolBeanGetsNoSecondGuard() {
        DataSource poolBean = wrap.apply(pool());
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(poolBean);

        assertThat(SpringDataSources.guard(lazy, wrap)).isSameAs(lazy);
        assertThat(target(lazy)).isSameAs(poolBean);
    }

    @Test
    void theLazyProxysReadOnlyTargetIsGuardedToo() {
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(pool());
        DataSource replica = pool();
        lazy.setReadOnlyDataSource(replica);

        SpringDataSources.guard(lazy, wrap);

        Field field = ReflectionUtils.findField(LazyConnectionDataSourceProxy.class, "readOnlyDataSource");
        ReflectionUtils.makeAccessible(field);
        assertThat(ReflectionUtils.getField(field, lazy)).isInstanceOf(GuardedDataSource.class);
    }

    @Test
    void aRoutingDataSourceIsGuardedAsAWholeEvenIfItsDefaultTargetIsGuarded() {
        TestRouting routing = routing(Map.of("A", wrap.apply(pool()), "B", pool()), null);

        assertThat(SpringDataSources.guard(routing, wrap)).isInstanceOf(GuardedDataSource.class);
    }

    @Test
    void routingTargetsAreFoundThroughGuardsAndProxiesWithAnUnkeyedDefaultLast() throws Exception {
        DataSource a = pool();
        DataSource b = pool();
        DataSource fallback = pool();
        TestRouting routing = routing(Map.of("A", a, "B", b), fallback);
        DataSource bean = new LazyConnectionDataSourceProxy(wrap.apply(routing));

        assertThat(SpringDataSources.routingTargets(bean))
                .containsOnlyKeys("A", "B", "(default)")
                .containsEntry("A", a).containsEntry("B", b).containsEntry("(default)", fallback);
        assertThat(SpringDataSources.routingTargets(routing(Map.of("A", a), a))).containsOnlyKeys("A");
        assertThat(SpringDataSources.routingTargets(wrap.apply(pool()))).isEmpty();
    }

    private static TestRouting routing(Map<Object, Object> targets, DataSource fallback) {
        TestRouting routing = new TestRouting();
        routing.setTargetDataSources(targets);
        if (fallback != null) {
            routing.setDefaultTargetDataSource(fallback);
        }
        routing.afterPropertiesSet();
        return routing;
    }

    static final class TestRouting extends AbstractRoutingDataSource {

        @Override
        protected Object determineCurrentLookupKey() {
            return null;
        }
    }
}
