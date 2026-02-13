package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Framework for simulating realistic backend behavior including latency and failures.
/// Used to test system resilience and behavior under various conditions.
public sealed interface BackendSimulation {
    /// Thread counter for unique naming.
    AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /// Shared scheduler for latency simulation.
    ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, BackendSimulation::createDaemonThread);

    private static Thread createDaemonThread(Runnable r) {
        var t = new Thread(r, "backend-simulation-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    // Shared validation causes
    Cause BASE_LATENCY_NEGATIVE = Causes.cause("baseLatencyMs must be >= 0");
    Cause JITTER_NEGATIVE = Causes.cause("jitterMs must be >= 0");
    Cause SPIKE_CHANCE_OUT_OF_RANGE = Causes.cause("spikeChance must be between 0 and 1");
    Cause SPIKE_LATENCY_NEGATIVE = Causes.cause("spikeLatencyMs must be >= 0");
    Cause FAILURE_RATE_OUT_OF_RANGE = Causes.cause("failureRate must be between 0 and 1");
    Cause ERROR_TYPES_EMPTY = Causes.cause("errorTypes cannot be null or empty");
    Cause SIMULATIONS_EMPTY = Causes.cause("simulations cannot be null or empty");

    /// Shutdown the scheduler. Should be called on application shutdown.
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

    /// Apply the simulation effect.
    ///
    /// @return Promise that completes after simulation effect (delay, or fails for failure injection)
    Promise<Unit> apply();

    /// No-op simulation that does nothing.
    record NoOp() implements BackendSimulation {
        public static final NoOp INSTANCE = new NoOp();

        @Override
        public Promise<Unit> apply() {
            return Promise.success(Unit.unit());
        }
    }

    /// Simulates network/processing latency with optional jitter and spikes.
    record LatencySimulation(long baseLatencyMs, long jitterMs, double spikeChance, long spikeLatencyMs)
    implements BackendSimulation {
        @Override
        public Promise<Unit> apply() {
            var delay = calculateDelay();
            return delay <= 0
                   ? Promise.success(Unit.unit())
                   : scheduleDelay(delay);
        }

        private long calculateDelay() {
            var random = ThreadLocalRandom.current();
            var delay = baseLatencyMs;
            if (jitterMs > 0) {
                delay += random.nextLong(jitterMs);
            }
            if (spikeChance > 0 && random.nextDouble() < spikeChance) {
                delay += spikeLatencyMs;
            }
            return delay;
        }

        private static Promise<Unit> scheduleDelay(long delayMs) {
            return Promise.promise(p -> SCHEDULER.schedule(() -> p.succeed(Unit.unit()),
                                                           delayMs,
                                                           TimeUnit.MILLISECONDS));
        }

        public static Result<LatencySimulation> latencySimulation(long baseMs,
                                                                  long jitterMs,
                                                                  double spikeChance,
                                                                  long spikeMs) {
            if (baseMs < 0) {
                return BASE_LATENCY_NEGATIVE.result();
            }
            if (jitterMs < 0) {
                return JITTER_NEGATIVE.result();
            }
            if (spikeChance < 0 || spikeChance > 1) {
                return SPIKE_CHANCE_OUT_OF_RANGE.result();
            }
            if (spikeMs < 0) {
                return SPIKE_LATENCY_NEGATIVE.result();
            }
            return Result.success(new LatencySimulation(baseMs, jitterMs, spikeChance, spikeMs));
        }

        public static LatencySimulation latencySimulation(long latencyMs) {
            return new LatencySimulation(latencyMs, 0, 0, 0);
        }

        public static LatencySimulation latencySimulation(long baseMs, long jitterMs) {
            return new LatencySimulation(baseMs, jitterMs, 0, 0);
        }

        public static Result<LatencySimulation> withSpikes(long baseMs,
                                                           long jitterMs,
                                                           double spikeChance,
                                                           long spikeMs) {
            return latencySimulation(baseMs, jitterMs, spikeChance, spikeMs);
        }
    }

    /// Simulates random failures at a configurable rate.
    record FailureInjection(double failureRate, List<SimulatedError> errorTypes) implements BackendSimulation {
        public FailureInjection(double failureRate, List<SimulatedError> errorTypes) {
            this.failureRate = failureRate;
            this.errorTypes = errorTypes == null
                              ? List.of()
                              : List.copyOf(errorTypes);
        }

        @Override
        public Promise<Unit> apply() {
            var random = ThreadLocalRandom.current();
            if (random.nextDouble() < failureRate) {
                return selectRandomError(random).promise();
            }
            return Promise.success(Unit.unit());
        }

        private SimulatedError selectRandomError(ThreadLocalRandom random) {
            return errorTypes.get(random.nextInt(errorTypes.size()));
        }

        public static Result<FailureInjection> failureInjection(double rate, List<SimulatedError> errors) {
            if (rate < 0 || rate > 1) {
                return FAILURE_RATE_OUT_OF_RANGE.result();
            }
            if (errors == null || errors.isEmpty()) {
                return ERROR_TYPES_EMPTY.result();
            }
            return Result.success(new FailureInjection(rate, errors));
        }

        public static Result<FailureInjection> withRate(double rate, SimulatedError... errors) {
            return failureInjection(rate, List.of(errors));
        }
    }

    /// Combines multiple simulations - all must succeed for the composite to succeed.
    /// Latency simulations are applied sequentially (delays add up).
    record Composite(List<BackendSimulation> simulations) implements BackendSimulation {
        public Composite(List<BackendSimulation> simulations) {
            this.simulations = simulations == null
                               ? List.of()
                               : List.copyOf(simulations);
        }

        @Override
        public Promise<Unit> apply() {
            var result = Promise.success(Unit.unit());
            for (var simulation : simulations) {
                result = result.flatMap(_ -> simulation.apply());
            }
            return result;
        }

        public static Result<Composite> composite(List<BackendSimulation> simulations) {
            if (simulations == null || simulations.isEmpty()) {
                return SIMULATIONS_EMPTY.result();
            }
            return Result.success(new Composite(simulations));
        }

        public static Result<Composite> composite(BackendSimulation... simulations) {
            return composite(List.of(simulations));
        }
    }

    /// Simulated error types for failure injection.
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
