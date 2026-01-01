package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framework for simulating realistic backend behavior including latency and failures.
 * Used to test system resilience and behavior under various conditions.
 */
public sealed interface BackendSimulation {
    /**
     * Thread counter for unique naming.
     */
    AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /**
     * Shared scheduler for latency simulation.
     */
    ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2,
                                                                          r -> {
                                                                              var t = new Thread(r,
                                                                                                 "backend-simulation-" + THREAD_COUNTER.incrementAndGet());
                                                                              t.setDaemon(true);
                                                                              return t;
                                                                          });

    /**
     * Shutdown the scheduler. Should be called on application shutdown.
     */
    static void shutdown() {
        SCHEDULER.shutdown();
        try{
            if (!SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
    }

    /**
     * Apply the simulation effect.
     *
     * @return Promise that completes after simulation effect (delay, or fails for failure injection)
     */
    Promise<Unit> apply();

    /**
     * No-op simulation that does nothing.
     */
    record NoOp() implements BackendSimulation {
        public static final NoOp INSTANCE = new NoOp();

        @Override
        public Promise<Unit> apply() {
            return Promise.success(Unit.unit());
        }
    }

    /**
     * Simulates network/processing latency with optional jitter and spikes.
     */
    record LatencySimulation(
    long baseLatencyMs,
    long jitterMs,
    double spikeChance,
    long spikeLatencyMs) implements BackendSimulation {
        public LatencySimulation {
            if (baseLatencyMs < 0) {
                throw new IllegalArgumentException("baseLatencyMs must be >= 0");
            }
            if (jitterMs < 0) {
                throw new IllegalArgumentException("jitterMs must be >= 0");
            }
            if (spikeChance < 0 || spikeChance > 1) {
                throw new IllegalArgumentException("spikeChance must be between 0 and 1");
            }
            if (spikeLatencyMs < 0) {
                throw new IllegalArgumentException("spikeLatencyMs must be >= 0");
            }
        }

        @Override
        public Promise<Unit> apply() {
            var random = ThreadLocalRandom.current();
            var delay = baseLatencyMs;
            if (jitterMs > 0) {
                delay += random.nextLong(jitterMs);
            }
            if (spikeChance > 0 && random.nextDouble() < spikeChance) {
                delay += spikeLatencyMs;
            }
            if (delay <= 0) {
                return Promise.success(Unit.unit());
            }
            final var finalDelay = delay;
            return Promise.<Unit>promise(p -> SCHEDULER.schedule(
            () -> p.succeed(Unit.unit()),
            finalDelay,
            TimeUnit.MILLISECONDS));
        }

        public static LatencySimulation fixed(long latencyMs) {
            return new LatencySimulation(latencyMs, 0, 0, 0);
        }

        public static LatencySimulation withJitter(long baseMs, long jitterMs) {
            return new LatencySimulation(baseMs, jitterMs, 0, 0);
        }

        public static LatencySimulation withSpikes(long baseMs, long jitterMs, double spikeChance, long spikeMs) {
            return new LatencySimulation(baseMs, jitterMs, spikeChance, spikeMs);
        }
    }

    /**
     * Simulates random failures at a configurable rate.
     */
    record FailureInjection(
    double failureRate,
    List<SimulatedError> errorTypes) implements BackendSimulation {
        public FailureInjection {
            if (failureRate < 0 || failureRate > 1) {
                throw new IllegalArgumentException("failureRate must be between 0 and 1");
            }
            if (errorTypes == null || errorTypes.isEmpty()) {
                throw new IllegalArgumentException("errorTypes cannot be null or empty");
            }
            errorTypes = List.copyOf(errorTypes);
        }

        @Override
        public Promise<Unit> apply() {
            var random = ThreadLocalRandom.current();
            if (random.nextDouble() < failureRate) {
                var error = errorTypes.get(random.nextInt(errorTypes.size()));
                return error.promise();
            }
            return Promise.success(Unit.unit());
        }

        public static FailureInjection withRate(double rate, SimulatedError... errors) {
            return new FailureInjection(rate, List.of(errors));
        }
    }

    /**
     * Combines multiple simulations - all must succeed for the composite to succeed.
     * Latency simulations are applied sequentially (delays add up).
     */
    record Composite(List<BackendSimulation> simulations) implements BackendSimulation {
        public Composite {
            if (simulations == null || simulations.isEmpty()) {
                throw new IllegalArgumentException("simulations cannot be null or empty");
            }
            simulations = List.copyOf(simulations);
        }

        @Override
        public Promise<Unit> apply() {
            var result = Promise.success(Unit.unit());
            for (var simulation : simulations) {
                result = result.flatMap(_ -> simulation.apply());
            }
            return result;
        }

        public static Composite of(BackendSimulation... simulations) {
            return new Composite(List.of(simulations));
        }
    }

    /**
     * Simulated error types for failure injection.
     */
    sealed interface SimulatedError extends Cause {
        record ServiceUnavailable(String serviceName) implements SimulatedError {
            @Override
            public String message() {
                return "Service unavailable: " + serviceName;
            }
        }

        record Timeout(String operation, long timeoutMs) implements SimulatedError {
            @Override
            public String message() {
                return "Timeout after " + timeoutMs + "ms: " + operation;
            }
        }

        record ConnectionRefused(String host, int port) implements SimulatedError {
            @Override
            public String message() {
                return "Connection refused: " + host + ":" + port;
            }
        }

        record DatabaseError(String query) implements SimulatedError {
            @Override
            public String message() {
                return "Database error executing: " + query;
            }
        }

        record RateLimited(int retryAfterSeconds) implements SimulatedError {
            @Override
            public String message() {
                return "Rate limited, retry after " + retryAfterSeconds + " seconds";
            }
        }

        record CustomError(String errorType, String errorMessage) implements SimulatedError {
            @Override
            public String message() {
                return errorType + ": " + errorMessage;
            }
        }
    }
}
