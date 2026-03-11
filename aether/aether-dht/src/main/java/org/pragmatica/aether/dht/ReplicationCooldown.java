package org.pragmatica.aether.dht;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages replication warmup after node startup.
/// Starts with RF=1 for fast boot, then signals readiness to upgrade to RF=3.
public interface ReplicationCooldown {
    long DEFAULT_COOLDOWN_DELAY_MS = 10_000L;
    int DEFAULT_RATE_PER_SECOND = 10_000;

    /// Start the cooldown timer. After delay, signals RF upgrade readiness.
    Promise<Unit> start();

    /// Stop the cooldown (e.g., on node shutdown).
    @SuppressWarnings("JBCT-RET-01") // Lifecycle shutdown — void required
    void stop();

    /// Whether cooldown has completed (RF upgrade ready).
    boolean isComplete();

    /// Create a replication cooldown with specified parameters.
    ///
    /// @param cooldownDelayMs delay before signaling RF upgrade readiness
    /// @param ratePerSecond max entries to replicate per second
    static ReplicationCooldown replicationCooldown(long cooldownDelayMs, int ratePerSecond) {
        return new DefaultReplicationCooldown(cooldownDelayMs, ratePerSecond);
    }

    /// Create a replication cooldown with default parameters (10s delay, 10000 entries/s).
    static ReplicationCooldown replicationCooldown() {
        return replicationCooldown(DEFAULT_COOLDOWN_DELAY_MS, DEFAULT_RATE_PER_SECOND);
    }
}

/// Package-private implementation of ReplicationCooldown.
final class DefaultReplicationCooldown implements ReplicationCooldown {
    private static final Logger log = LoggerFactory.getLogger(ReplicationCooldown.class);

    private final long cooldownDelayMs;
    private final int ratePerSecond;
    private final AtomicBoolean complete = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(DefaultReplicationCooldown::createDaemonThread);

    DefaultReplicationCooldown(long cooldownDelayMs, int ratePerSecond) {
        this.cooldownDelayMs = cooldownDelayMs;
        this.ratePerSecond = ratePerSecond;
    }

    @Override
    public Promise<Unit> start() {
        var promise = Promise.<Unit>promise();
        scheduler.schedule(() -> completeWarmup(promise), cooldownDelayMs, TimeUnit.MILLISECONDS);
        return promise;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01") // Lifecycle shutdown — void required
    public void stop() {
        scheduler.shutdownNow();
    }

    @Override
    public boolean isComplete() {
        return complete.get();
    }

    @SuppressWarnings("JBCT-RET-01") // Callback for scheduled executor — void required
    private void completeWarmup(Promise<Unit> promise) {
        log.info("Replication cooldown complete after {}ms delay, RF upgrade ready (rate limit: {}/s)",
                 cooldownDelayMs,
                 ratePerSecond);
        complete.set(true);
        promise.succeed(Unit.unit());
    }

    private static Thread createDaemonThread(Runnable runnable) {
        var thread = new Thread(runnable, "replication-cooldown");
        thread.setDaemon(true);
        return thread;
    }
}
