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
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
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
 *
 * <p>Setup failures name what to check: the person running the tests is often not the one who
 * wrote the app's wiring, and "permission denied" alone does not say which credentials matter.
 */
public final class TenantGuardExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(TenantGuardExtension.class);
    private static final System.Logger LOG = System.getLogger(TenantGuardExtension.class.getName());
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        TenantIsolationTest settings = settings(context);
        ApplicationContext spring = SpringExtension.getApplicationContext(context);
        TenantGuard guard = spring.getBean(TenantGuard.class);
        ObservedDataSources dataSources = ObservedDataSources.select(spring, settings.dataSources(), guard);
        List<String> warnings = Stream.concat(dataSources.warnings().stream(), SchedulingCheck.warnings(spring).stream())
                .toList();
        warnings.forEach(warning -> LOG.log(warning.equals(SchedulingCheck.SUPPRESSED)
                ? System.Logger.Level.INFO : System.Logger.Level.WARNING, warning));

        guard.deactivate();
        guard.reset();
        boolean armed = false;
        try {
            spring.getBeanProvider(TenantSeeder.class).ifAvailable(seeder -> {
                seeder.seed(settings.victim());
                seeder.seed(settings.attacker());
            });
            TableDiscovery.Result tables = dataSources.install(settings.tenantColumn(), settings.tables());
            guard.activate();
            if (settings.selfCheck()) {
                dataSources.selfCheck(guard);
            }
            guard.reset();
            context.getStore(NAMESPACE).put(ObserverSetup.class, new ObserverSetup(
                    settings.tenantColumn(), tables.tables(), tables.skipped(), settings.selfCheck(), warnings));
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
    static TableDiscovery.Result install(DataSource dataSource, String column, String[] explicitTables) {
        try (Connection owner = dataSource.getConnection()) {
            TableDiscovery.Result tables = explicitTables.length > 0
                    ? new TableDiscovery.Result(List.of(explicitTables), Map.of())
                    : TableDiscovery.discover(owner, column);
            tables.skipped().forEach((table, reason) ->
                    LOG.log(System.Logger.Level.WARNING, "TenantGuard does not observe table ''{0}'': {1}", table, reason));
            if (tables.tables().isEmpty()) {
                throw new IllegalStateException(("TenantGuard is blind: no observable table in the current schema "
                        + "has column '%s' (skipped: %s).%nIt observes shared tables that carry the tenant in a column; "
                        + "schema-per-tenant designs without one (Hibernate schema multi-tenancy, a schema or database "
                        + "per tenant) are out of scope.").formatted(column, tables.skipped()));
            }
            installAs(owner, column, tables.tables());
            return tables;
        } catch (SQLException e) {
            throw new IllegalStateException(("TenantGuard could not read the schema through the app's DataSource "
                    + "(SQLState %s: %s).%nCheck the spring.datasource.* settings of the test context and that the "
                    + "migrations ran.").formatted(e.getSQLState(), e.getMessage()), e);
        }
    }

    private static void installAs(Connection owner, String column, List<String> tables) throws SQLException {
        try {
            ObserverInstaller.install(owner, column, tables);
            if (!owner.getAutoCommit()) {
                owner.commit();
            }
        } catch (SQLException e) {
            String user = owner.getMetaData().getUserName();
            String hint = INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())
                    ? "The app's DataSource user must own the tenant tables (create the schema with Flyway or "
                            + "migrations as that user, not ddl-auto under another role) and be allowed to create roles."
                    : "The schema must exist (migrations ran) and be owned by the app's DataSource user.";
            throw new IllegalStateException("TenantGuard could not install the observer as user '%s' (SQLState %s: %s).%n%s"
                    .formatted(user, e.getSQLState(), e.getMessage(), hint), e);
        }
    }

    /** A blind self-check keeps its own message; a self-check that cannot even run says what it needed. */
    static void selfCheck(DataSource dataSource, TenantGuard guard) {
        try {
            SelfCheck.verify(dataSource, guard);
        } catch (SQLException e) {
            throw new IllegalStateException(("TenantGuard self-check could not run: %s.%nIt needs the observer "
                    + "installed (role %s, table %s) and a DataSource user that may SET ROLE to the configured role.")
                    .formatted(e.getMessage(), ObserverInstaller.ROLE, ObserverInstaller.PROBE_TABLE), e);
        }
    }

    static DataSource guardedDataSource(ApplicationContext spring) throws SQLException {
        List<String> names = List.of(spring.getBeanNamesForType(DataSource.class));
        DataSource dataSource;
        try {
            dataSource = spring.getBean(DataSource.class);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("TenantGuard found several DataSource beans %s and none is @Primary; "
                    .formatted(names) + "mark the one the app's repositories use as @Primary.", e);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException("TenantGuard found no DataSource bean: check spring.datasource.url/username/"
                    + "password (or the environment keys your app reads them from) in the test context.", e);
        }
        requireGuarded(names.size() == 1 ? names.getFirst() : "@Primary of " + names, dataSource);
        return dataSource;
    }

    static void requireGuarded(String name, DataSource dataSource) throws SQLException {
        if (!dataSource.isWrapperFor(GuardedDataSource.class)) {
            throw new IllegalStateException(("The DataSource bean '%s' (%s) is not a GuardedDataSource: import "
                    + "TenantGuardConfiguration (@TenantIsolationTest does that), and check which bean created the "
                    + "DataSource; one built outside the context or replaced after post-processing is never wrapped.")
                    .formatted(name, dataSource.getClass().getSimpleName()));
        }
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
