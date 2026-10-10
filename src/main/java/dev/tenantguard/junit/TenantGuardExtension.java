package dev.tenantguard.junit;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.SelfCheck;
import dev.tenantguard.TableDiscovery;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.TenantSeeder;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.beans.factory.annotation.ParameterResolutionDelegate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Lifecycle behind {@link TenantIsolationTest}. Holds no state of its own: the Spring context owns
 * the guard, the per-test {@link ObserverSetup} lives in the method's {@link ExtensionContext.Store}.
 *
 * <p>Ordering: JUnit runs extension {@code beforeEach} callbacks before the test class's own
 * {@code @BeforeEach} methods. So a test that seeds in {@code @BeforeEach} runs after the observer
 * is installed and the guard is armed; it must deactivate the guard while seeding (rows are then
 * written as the owner and bypass the observer) and re-activate and reset afterwards. Installing
 * first is harmless: it only adds a role, a policy and grants, and the owner bypasses the policy.
 */
public final class TenantGuardExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(TenantGuardExtension.class);
    private static final System.Logger LOG = System.getLogger(TenantGuardExtension.class.getName());

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        TenantIsolationTest settings = settings(context);
        ApplicationContext spring = SpringExtension.getApplicationContext(context);
        TenantGuard guard = spring.getBean(TenantGuard.class);
        DataSource dataSource = guardedDataSource(spring);

        guard.deactivate();
        guard.reset();
        boolean armed = false;
        try {
            spring.getBeanProvider(TenantSeeder.class).ifAvailable(seeder -> {
                seeder.seed(settings.victim());
                seeder.seed(settings.attacker());
            });
            TableDiscovery.Result tables = install(dataSource, settings);
            guard.activate();
            if (settings.selfCheck()) {
                SelfCheck.verify(dataSource, guard);
            }
            guard.reset();
            context.getStore(NAMESPACE).put(ObserverSetup.class, new ObserverSetup(
                    settings.tenantColumn(), tables.tables(), tables.skipped(), settings.selfCheck()));
            armed = true;
        } finally {
            if (!armed) {
                guard.deactivate();
            }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantGuard guard = SpringExtension.getApplicationContext(context).getBean(TenantGuard.class);
        guard.deactivate();
        guard.reset();
    }

    /** Method parameters only; anything Spring would autowire ({@code @Autowired}, constructors) stays Spring's. */
    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        if (!(parameter.getDeclaringExecutable() instanceof Method)
                || ParameterResolutionDelegate.isAutowirable(parameter.getParameter(), parameter.getIndex())) {
            return false;
        }
        Class<?> type = parameter.getParameter().getType();
        return type == TenantGuard.class || type == ObserverSetup.class || type == TenantSeeder.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        if (type == ObserverSetup.class) {
            ObserverSetup setup = context.getStore(NAMESPACE).get(ObserverSetup.class, ObserverSetup.class);
            if (setup == null) {
                throw new ParameterResolutionException("ObserverSetup exists only in @Test and @BeforeEach/@AfterEach methods");
            }
            return setup;
        }
        ApplicationContext spring = SpringExtension.getApplicationContext(context);
        if (type == TenantGuard.class) {
            return spring.getBean(TenantGuard.class);
        }
        return spring.getBeanProvider(TenantSeeder.class).getIfAvailable(() -> {
            throw new ParameterResolutionException("No TenantSeeder bean in the application context");
        });
    }

    /**
     * The guard is inactive here, so the guarded DataSource runs {@code RESET ROLE}: the connection
     * uses the app's own credentials, which own the tables when the schema comes from Flyway.
     */
    private static TableDiscovery.Result install(DataSource dataSource, TenantIsolationTest settings)
            throws SQLException {
        String column = settings.tenantColumn();
        try (Connection owner = dataSource.getConnection()) {
            TableDiscovery.Result tables = settings.tables().length > 0
                    ? new TableDiscovery.Result(List.of(settings.tables()), Map.of())
                    : TableDiscovery.discover(owner, column);
            tables.skipped().forEach((table, reason) ->
                    LOG.log(System.Logger.Level.WARNING, "TenantGuard does not observe table ''{0}'': {1}", table, reason));
            if (tables.tables().isEmpty()) {
                throw new IllegalStateException(("TenantGuard is blind: no observable table in the current schema "
                        + "has column '%s' (skipped: %s)").formatted(column, tables.skipped()));
            }
            ObserverInstaller.install(owner, column, tables.tables());
            if (!owner.getAutoCommit()) {
                owner.commit();
            }
            return tables;
        }
    }

    private static DataSource guardedDataSource(ApplicationContext spring) throws SQLException {
        DataSource dataSource = spring.getBean(DataSource.class);
        if (!dataSource.isWrapperFor(GuardedDataSource.class)) {
            throw new IllegalStateException("The DataSource bean is not a GuardedDataSource; "
                    + "TenantGuardConfiguration must be imported (@TenantIsolationTest does that)");
        }
        return dataSource;
    }

    /** Looks at the test class, its superclasses ({@code @Inherited}) and, for {@code @Nested}, enclosing classes. */
    private static TenantIsolationTest settings(ExtensionContext context) {
        for (Class<?> type = context.getRequiredTestClass(); type != null; type = type.getEnclosingClass()) {
            var found = AnnotationSupport.findAnnotation(type, TenantIsolationTest.class);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("TenantGuardExtension needs @TenantIsolationTest on the test class");
    }
}
