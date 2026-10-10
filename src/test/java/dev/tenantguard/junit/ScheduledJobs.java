package dev.tenantguard.junit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * An app with {@code @EnableScheduling}, like the field run's status poller. The job deliberately
 * touches no table: its context stays cached while other test classes run, and a job reading tenant
 * rows would then be a real source of noise. Not in the sample package, so the sample app's
 * component scan never picks it up.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ScheduledJobs {

    static final class Poller {

        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch firstRun = new CountDownLatch(1);

        @Scheduled(fixedDelay = 20)
        void poll() {
            runs.incrementAndGet();
            firstRun.countDown();
        }
    }

    @Bean
    Poller poller() {
        return new Poller();
    }
}
