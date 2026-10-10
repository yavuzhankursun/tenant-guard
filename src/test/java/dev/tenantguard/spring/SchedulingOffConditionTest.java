package dev.tenantguard.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** No database: only which beans {@link TenantGuardConfiguration} registers for the scheduling switch. */
class SchedulingOffConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TenantGuardConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"off", "OFF", "false"})
    @DisplayName("off (ve YAML'ın tırnaksız off'u okuduğu false): no-op scheduler kayıtlı")
    void offRegistersTheNoOpScheduler(String value) {
        runner.withPropertyValues(TenantGuardConfiguration.SCHEDULING_PROPERTY + "=" + value)
                .run(context -> assertThat(context).hasSingleBean(NoOpTaskScheduler.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "true", ""})
    @DisplayName("Başka değer: hiçbir şey kaydedilmez")
    void otherValuesRegisterNothing(String value) {
        runner.withPropertyValues(TenantGuardConfiguration.SCHEDULING_PROPERTY + "=" + value)
                .run(context -> assertThat(context).doesNotHaveBean(NoOpTaskScheduler.class));
    }

    @Test
    @DisplayName("Property yok: varsayılan, scheduling'e dokunulmaz")
    void absentRegistersNothing() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NoOpTaskScheduler.class));
    }

    @Test
    @DisplayName("No-op scheduler hiçbir görevi çalıştırmaz, iptal edilmiş future döner ve sayar")
    void noOpNeverRuns() {
        NoOpTaskScheduler scheduler = new NoOpTaskScheduler();
        Runnable fail = () -> {
            throw new AssertionError("must not run");
        };

        Future<?> once = scheduler.schedule(fail, Instant.now());
        Future<?> rate = scheduler.scheduleAtFixedRate(fail, Duration.ofMillis(1));
        Future<?> delay = scheduler.scheduleWithFixedDelay(fail, Duration.ofMillis(1));

        assertThat(once).isCancelled();
        assertThat(rate).isDone();
        assertThat(delay).isCancelled();
        assertThat(scheduler.suppressedTasks()).isEqualTo(3);
    }
}
