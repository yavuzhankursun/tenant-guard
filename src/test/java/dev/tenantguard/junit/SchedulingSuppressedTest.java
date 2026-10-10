package dev.tenantguard.junit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.sample.SampleApp;
import dev.tenantguard.spring.NoOpTaskScheduler;
import dev.tenantguard.spring.TenantGuardConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in: {@code tenantguard.scheduling=off} hands every {@code @Scheduled} job to a scheduler that never runs it. */
@SpringBootTest(classes = {SampleApp.class, ScheduledJobs.class, SchedulingSuppressedTest.OwnScheduler.class},
        properties = TenantGuardConfiguration.SCHEDULING_PROPERTY + "=off")
@TenantIsolationTest(tenantColumn = "tenant_id", tables = {"invoice", "invoice_line"})
@DirtiesContext
class SchedulingSuppressedTest {

    /** An app that picks its own scheduler through a configurer: the guard's configurer must still win. */
    @Configuration(proxyBeanMethods = false)
    static class OwnScheduler implements SchedulingConfigurer {

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            ThreadPoolTaskScheduler own = new ThreadPoolTaskScheduler();
            own.setDaemon(true);
            own.initialize();
            registrar.setScheduler(own);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GuardedTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", GuardedTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", GuardedTestBase.POSTGRES::getPassword);
    }

    @Autowired
    ScheduledJobs.Poller poller;

    @Autowired
    NoOpTaskScheduler noOp;

    @Autowired
    TaskScheduler taskScheduler;

    @Test
    void jobsAreHandedToTheNoOpSchedulerAndNeverRun(ObserverSetup setup) {
        assertThat(setup.warnings()).singleElement().asString()
                .contains("scheduling is suppressed", "tenantguard.scheduling=off");
        assertThat(taskScheduler).as("the @Primary scheduler").isSameAs(noOp);
        assertThat(noOp.suppressedTasks()).as("the poller was registered with it").isPositive();
        assertThat(poller.runs).hasValue(0);
    }
}
