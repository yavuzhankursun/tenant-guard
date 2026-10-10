package dev.tenantguard.spring;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * A {@link TaskScheduler} that accepts every task and runs none, registered by
 * {@code tenantguard.scheduling=off}. Why: the guard's actor is process-wide, so a {@code @Scheduled}
 * job that reads every tenant's rows while a test acts as tenant B is reported as B's leak. Each
 * call returns an already-cancelled future, which is what a cancelled task looks like to callers.
 */
public final class NoOpTaskScheduler implements TaskScheduler {

    private final AtomicInteger suppressed = new AtomicInteger();

    /** How many tasks were handed to this scheduler and not run. */
    public int suppressedTasks() {
        return suppressed.get();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return suppress();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return suppress();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return suppress();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return suppress();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return suppress();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return suppress();
    }

    private ScheduledFuture<?> suppress() {
        suppressed.incrementAndGet();
        return Cancelled.INSTANCE;
    }

    /** Stateless, so one instance serves every suppressed task. Not an enum: Comparable would clash with Delayed's. */
    private static final class Cancelled implements ScheduledFuture<Object> {

        static final Cancelled INSTANCE = new Cancelled();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(0, other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new CancellationException("suppressed by tenantguard.scheduling=off");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return get();
        }
    }
}
