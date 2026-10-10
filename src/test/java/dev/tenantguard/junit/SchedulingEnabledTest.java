package dev.tenantguard.junit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tenantguard.GuardedTestBase;
import dev.tenantguard.sample.SampleApp;
import dev.tenantguard.spring.NoOpTaskScheduler;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Scheduling left on: jobs run during the test under the guard's global actor, so the extension
 * warns and records it. Dirties the context so the poller stops with this class.
 */
@SpringBootTest(classes = {SampleApp.class, ScheduledJobs.class})
@TenantIsolationTest(tenantColumn = "tenant_id", tables = {"invoice", "invoice_line"})
@DirtiesContext
class SchedulingEnabledTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GuardedTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", GuardedTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", GuardedTestBase.POSTGRES::getPassword);
    }

    @Autowired
    ScheduledJobs.Poller poller;

    @Autowired
    ApplicationContext context;

    @Test
    void warnsThatScheduledJobsRunUnderTheGuardsActor(ObserverSetup setup) throws InterruptedException {
        assertThat(setup.warnings()).singleElement().asString()
                .contains("scheduling is enabled", "@Scheduled", "tenantguard.scheduling=off");
        assertThat(poller.firstRun.await(5, TimeUnit.SECONDS)).as("the job really runs").isTrue();
        assertThat(context.getBeanNamesForType(NoOpTaskScheduler.class)).isEmpty();
    }
}
