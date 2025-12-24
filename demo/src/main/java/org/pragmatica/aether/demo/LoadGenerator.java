package org.pragmatica.aether.demo;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Generates continuous load against the cluster for demo purposes.
 * Tracks success/failure rates and latency.
 */
public final class LoadGenerator {
    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private static final TimeSpan REQUEST_TIMEOUT = TimeSpan.timeSpan(3).seconds();

    private final Supplier<List<AetherNode>> nodeSupplier;
    private final DemoMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger currentRate = new AtomicInteger(1000);
    private final AtomicInteger targetRate = new AtomicInteger(1000);
    private final AtomicLong requestCounter = new AtomicLong(0);

    private ScheduledExecutorService scheduler;
    private Thread loadThread;

    private LoadGenerator(Supplier<List<AetherNode>> nodeSupplier, DemoMetrics metrics) {
        this.nodeSupplier = nodeSupplier;
        this.metrics = metrics;
    }

    public static LoadGenerator loadGenerator(Supplier<List<AetherNode>> nodeSupplier, DemoMetrics metrics) {
        return new LoadGenerator(nodeSupplier, metrics);
    }

    /**
     * Start generating load at the specified rate.
     */
    public void start(int requestsPerSecond) {
        if (running.getAndSet(true)) {
            log.warn("Load generator already running");
            return;
        }

        currentRate.set(requestsPerSecond);
        targetRate.set(requestsPerSecond);

        log.info("Starting load generator at {} req/sec", requestsPerSecond);

        scheduler = Executors.newScheduledThreadPool(2);

        // Rate adjustment scheduler (for ramping)
        scheduler.scheduleAtFixedRate(this::adjustRate, 100, 100, TimeUnit.MILLISECONDS);

        // Load generation thread
        loadThread = new Thread(this::generateLoad, "load-generator");
        loadThread.start();
    }

    /**
     * Stop generating load.
     */
    public void stop() {
        log.info("Stopping load generator");
        running.set(false);

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (loadThread != null) {
            loadThread.interrupt();
            try {
                loadThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Set the target request rate.
     */
    public void setRate(int requestsPerSecond) {
        log.info("Setting load rate to {} req/sec", requestsPerSecond);
        currentRate.set(requestsPerSecond);
        targetRate.set(requestsPerSecond);
    }

    /**
     * Gradually ramp up to target rate over specified duration.
     */
    public void rampUp(int targetRateValue, long durationMillis) {
        log.info("Ramping load from {} to {} req/sec over {}ms",
                 currentRate.get(), targetRateValue, durationMillis);
        targetRate.set(targetRateValue);
        // Rate adjustment happens in adjustRate()
    }

    /**
     * Get current request rate setting.
     */
    public int currentRate() {
        return currentRate.get();
    }

    /**
     * Get target request rate.
     */
    public int targetRate() {
        return targetRate.get();
    }

    /**
     * Check if generator is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    private void generateLoad() {
        while (running.get()) {
            try {
                var rate = currentRate.get();
                if (rate <= 0) {
                    Thread.sleep(100);
                    continue;
                }

                var intervalMicros = 1_000_000 / rate;
                var startNanos = System.nanoTime();

                sendRequest();

                // Sleep for remaining interval
                var elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                var sleepMicros = intervalMicros - elapsedMicros;
                if (sleepMicros > 0) {
                    Thread.sleep(sleepMicros / 1000, (int) ((sleepMicros % 1000) * 1000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Error generating load: {}", e.getMessage());
            }
        }
    }

    private void sendRequest() {
        var nodes = nodeSupplier.get();
        if (nodes.isEmpty()) {
            return;
        }

        var requestId = requestCounter.incrementAndGet();
        var nodeIndex = (int) (requestId % nodes.size());
        var node = nodes.get(nodeIndex);

        var startTime = System.nanoTime();

        // Create a test artifact for this request
        var artifact = Artifact.artifact("org.demo:load-test:" + requestId + ".0.0");
        artifact.onFailure(_ -> metrics.recordFailure(System.nanoTime() - startTime))
                .onSuccess(art -> {
                    var key = new SliceNodeKey(art, node.self());
                    var value = new SliceNodeValue(SliceState.LOADED);

                    node.apply(List.of(new KVCommand.Put<>(key, value)))
                        .timeout(REQUEST_TIMEOUT)
                        .onSuccess(_ -> metrics.recordSuccess(System.nanoTime() - startTime))
                        .onFailure(_ -> metrics.recordFailure(System.nanoTime() - startTime));
                });
    }

    private void adjustRate() {
        var current = currentRate.get();
        var target = targetRate.get();

        if (current == target) {
            return;
        }

        // Adjust by 10% toward target
        var delta = (target - current) / 10;
        if (delta == 0) {
            delta = target > current ? 1 : -1;
        }

        var newRate = current + delta;
        if ((delta > 0 && newRate > target) || (delta < 0 && newRate < target)) {
            newRate = target;
        }

        currentRate.set(newRate);
    }
}
