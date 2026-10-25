package app.vaultdoc.gateway.documents;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drains the tag re-index retry queue every
 * {@code vaultdoc.search.tag-resync-interval} (default 30 s) on one daemon
 * thread. A dedicated executor rather than {@code @EnableScheduling}: nothing
 * else in the app schedules work, and this keeps the sweep's lifecycle tied to
 * the context (stopped before the datasource closes).
 */
@Component
public class TagIndexResyncScheduler implements SmartLifecycle {

    private final TagIndexSynchronizer synchronizer;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public TagIndexResyncScheduler(
            TagIndexSynchronizer synchronizer,
            @Value("${vaultdoc.search.tag-resync-interval:30s}") Duration interval) {
        this.synchronizer = synchronizer;
        this.interval = interval == null || interval.isZero() || interval.isNegative() ? Duration.ofSeconds(30) : interval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("tag-index-resync").factory());
        executor.scheduleWithFixedDelay(synchronizer::retryPending,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    Duration interval() {
        return interval;
    }
}
