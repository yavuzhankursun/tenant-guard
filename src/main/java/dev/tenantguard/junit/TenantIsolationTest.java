package dev.tenantguard.junit;

import dev.tenantguard.spring.TenantGuardConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;

/**
 * Arms a {@link dev.tenantguard.TenantGuard} around every test of a Spring test class
 * ({@code @SpringBootTest} or any other {@code SpringExtension}-based test).
 *
 * <p>Before each test {@link TenantGuardExtension}: deactivates and resets the guard, calls the
 * {@link dev.tenantguard.TenantSeeder} bean (if any) for {@link #victim()} then {@link #attacker()},
 * installs the observer, activates the guard, runs the self-check and resets the guard. After each
 * test it deactivates and resets. Test methods may declare {@code TenantGuard},
 * {@link ObserverSetup} and {@code TenantSeeder} parameters.
 *
 * <p>Assumption: the schema is created by Flyway (or the app) with the application's own
 * credentials, which therefore own the tables. The observer is installed through the guarded
 * {@code DataSource} while the guard is inactive, i.e. as that owner. Hibernate {@code ddl-auto}
 * with a non-owner role is not supported.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(TenantGuardExtension.class)
@Import(TenantGuardConfiguration.class)
public @interface TenantIsolationTest {

    /** Column holding the tenant id, e.g. {@code tenant_id}. Unquoted lower-case identifier. */
    String tenantColumn();

    /**
     * Tables to observe. Empty (the default): every base table of the current schema that has
     * {@link #tenantColumn()}, except {@code tg_probe} and {@code flyway_schema_history}; tables that
     * already have an RLS policy are skipped and reported in {@link ObserverSetup#skippedTables()}.
     * Explicit tables with an existing policy fail the test instead.
     */
    String[] tables() default {};

    /** Tenant whose rows must not leak; seeded first, so a seeder may start by truncating. */
    String victim() default "A";

    /** Tenant the test acts as. */
    String attacker() default "B";

    /** Fail every test when the guard would be blind (superuser, BYPASSRLS, NOTICE filtered). */
    boolean selfCheck() default true;
}
