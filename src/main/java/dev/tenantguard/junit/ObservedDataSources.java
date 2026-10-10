package dev.tenantguard.junit;

import dev.tenantguard.GuardedDataSource;
import dev.tenantguard.ObserverInstaller;
import dev.tenantguard.TableDiscovery;
import dev.tenantguard.TenantGuard;
import dev.tenantguard.spring.SpringDataSources;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;

/**
 * The DataSources a test observes ({@link TenantIsolationTest#dataSources()}), the databases the
 * observer is installed and self-checked in, and a warning for every other DataSource bean.
 *
 * <p>A routing DataSource is expanded to its resolved targets: during setup the lookup key picks one
 * target at most, and the others would stay without observer. Each target is reached through a
 * guard of its own for setup only; the app's connections still go through the routing bean's guard.
 *
 * <p>DataSource beans the test does not observe are switched off ({@link GuardedDataSource#setObserving}),
 * so their reads run as the app's own user, unobserved, as the warning says; otherwise they would
 * switch to the observed role on a database the observer was never installed in and fail there.
 */
final class ObservedDataSources {

    static final String NOT_OBSERVED = "DataSource '%s' is not observed: TenantGuard installs and self-checks the "
            + "observer only through %s, and reads through '%s' run with the app's own role, so they are never "
            + "reported. Observe it too with @TenantIsolationTest(dataSources = {%s}).";

    /** One database the observer is installed in; {@code label} names it in messages. */
    private record Target(String label, DataSource dataSource) {
    }

    private final List<Target> targets;
    private final List<String> warnings;
    private final boolean qualifyMessages;

    private ObservedDataSources(List<Target> targets, List<String> warnings, boolean qualifyMessages) {
        this.targets = List.copyOf(targets);
        this.warnings = List.copyOf(warnings);
        this.qualifyMessages = qualifyMessages;
    }

    static ObservedDataSources select(ApplicationContext spring, String[] names, TenantGuard guard) throws SQLException {
        Map<String, DataSource> beans = spring.getBeansOfType(DataSource.class);
        List<String> observed = names.length > 0 ? List.of(names) : List.of(primaryName(spring, beans));
        Set<Object> covered = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Target> targets = new ArrayList<>();
        boolean routed = false;
        for (String name : observed) {
            DataSource dataSource = beans.get(name);
            if (dataSource == null) {
                throw new IllegalStateException(("TenantGuard found no DataSource bean named '%s' (from "
                        + "@TenantIsolationTest(dataSources)); DataSource beans: %s").formatted(name, beans.keySet()));
            }
            TenantGuardExtension.requireGuarded(name, dataSource);
            cover(covered, dataSource);
            Map<String, DataSource> routes = SpringDataSources.routingTargets(dataSource);
            routed |= !routes.isEmpty();
            if (routes.isEmpty()) {
                targets.add(new Target("'%s'".formatted(name), dataSource));
            }
            routes.forEach((key, target) -> {
                cover(covered, target);
                targets.add(new Target("'%s' routing key %s".formatted(name, key),
                        new GuardedDataSource(target, guard, ObserverInstaller.ROLE)));
            });
        }
        return new ObservedDataSources(targets, switchOffTheRest(beans, covered, observed),
                routed || targets.size() > 1);
    }

    /** Switches every DataSource bean on or off and warns about the ones that are off. */
    private static List<String> switchOffTheRest(Map<String, DataSource> beans, Set<Object> covered, List<String> observed) {
        List<String> warnings = new ArrayList<>();
        beans.forEach((name, dataSource) -> {
            GuardedDataSource own = guardOf(dataSource);
            boolean isObserved = covered.contains(dataSource) || (own != null && covered.contains(own));
            if (own != null) {
                own.setObserving(isObserved);
            }
            if (!isObserved) {
                String suggestion = Stream.concat(observed.stream(), Stream.of(name))
                        .map(each -> "\"" + each + "\"").collect(Collectors.joining(", "));
                warnings.add(NOT_OBSERVED.formatted(name, observed, name, suggestion));
            }
        });
        return warnings;
    }

    private static void cover(Set<Object> covered, DataSource dataSource) {
        covered.add(dataSource);
        GuardedDataSource own = guardOf(dataSource);
        if (own != null) {
            covered.add(own);
        }
    }

    private static GuardedDataSource guardOf(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(GuardedDataSource.class) ? dataSource.unwrap(GuardedDataSource.class) : null;
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    private static String primaryName(ApplicationContext spring, Map<String, DataSource> beans) throws SQLException {
        DataSource primary = TenantGuardExtension.guardedDataSource(spring);
        return beans.entrySet().stream().filter(bean -> bean.getValue() == primary).map(Map.Entry::getKey)
                .findFirst().orElse("dataSource");
    }

    List<String> warnings() {
        return warnings;
    }

    /**
     * Installs through every target; the tables are the union, in first-seen order. {@code guard}
     * trusts each target's nonce, so every observed database's notices count.
     */
    TableDiscovery.Result install(TenantGuard guard, String column, String[] tables, String[] schemas) {
        Set<String> installed = new LinkedHashSet<>();
        Map<String, String> skipped = new TreeMap<>();
        for (Target target : targets) {
            TableDiscovery.Result result = through(target,
                    () -> TenantGuardExtension.install(target.dataSource(), guard, column, tables, schemas));
            installed.addAll(result.tables());
            skipped.putAll(result.skipped());
        }
        return new TableDiscovery.Result(List.copyOf(installed), skipped);
    }

    void selfCheck(TenantGuard guard) {
        targets.forEach(target -> through(target, () -> {
            TenantGuardExtension.selfCheck(target.dataSource(), guard);
            return null;
        }));
    }

    /** With several databases a message must say which one failed. */
    private <T> T through(Target target, Supplier<T> step) {
        try {
            return step.get();
        } catch (IllegalStateException e) {
            if (!qualifyMessages) {
                throw e;
            }
            throw new IllegalStateException("Through DataSource %s: %s".formatted(target.label(), e.getMessage()), e);
        }
    }
}
