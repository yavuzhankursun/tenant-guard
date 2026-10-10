package dev.tenantguard.junit;

import dev.tenantguard.spring.NoOpTaskScheduler;
import dev.tenantguard.spring.TenantGuardConfiguration;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Detects the app's scheduling. Why it matters: the guard's actor is process-wide, so a job that
 * fires mid-test and reads every tenant's rows (a status poller, a cleanup) is attributed to the
 * test's tenant and shows up as a leak. Only bean names are looked up, nothing is instantiated.
 */
final class SchedulingCheck {

    static final String ENABLED = ("Spring scheduling is enabled (@EnableScheduling or a TaskScheduler bean): "
            + "@Scheduled jobs that run during a test read rows under the guard's global actor and are reported "
            + "as that tenant's leaks. Set %s=off to suppress them.").formatted(TenantGuardConfiguration.SCHEDULING_PROPERTY);

    static final String SUPPRESSED = ("Spring scheduling is suppressed (%s=off): @Scheduled jobs are handed to a "
            + "NoOpTaskScheduler and do not run during the tests.").formatted(TenantGuardConfiguration.SCHEDULING_PROPERTY);

    private SchedulingCheck() {
    }

    static List<String> warnings(ApplicationContext spring) {
        boolean suppressed = names(spring, NoOpTaskScheduler.class).length > 0;
        boolean annotations = names(spring, ScheduledAnnotationBeanPostProcessor.class).length > 0;
        boolean schedulers = Arrays.stream(names(spring, TaskScheduler.class))
                .anyMatch(name -> !spring.isTypeMatch(name, NoOpTaskScheduler.class));
        if (!annotations && !schedulers) {
            return List.of();
        }
        return List.of(suppressed ? SUPPRESSED : ENABLED);
    }

    private static String[] names(ApplicationContext spring, Class<?> type) {
        return spring.getBeanNamesForType(type, true, false);
    }
}
