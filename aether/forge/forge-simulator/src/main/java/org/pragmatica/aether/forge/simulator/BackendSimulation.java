package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;

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
    static Result<Unit> shutdown() {
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
        return unitResult();
    }

    /// Apply the simulation effect.
    ///
    /// @return Promise that completes after simulation effect (delay, or fails for failure injection)
    Promise<Unit> apply();

    /// No-op simulation that does nothing.
    record NoOp() implements BackendSimulation {
        private static final NoOp INSTANCE = noOp().unwrap();

        public static Result<NoOp> noOp() {
            return success(new NoOp());
        }

        @Override
        public Promise<Unit> apply() {
            return Promise.success(unit());
        }
    }

    /// Simulates network/processing latency with optional jitter and spikes.
    record LatencySimulation(long baseLatencyMs, long jitterMs, double spikeChance, long spikeLatencyMs)
    implements BackendSimulation {
        @Override
        public Promise<Unit> apply() {
            var delay = calculateDelay();
            if (Verify.Is.nonPositive(delay)) {
                return Promise.success(unit());
            }
            return scheduleDelay(delay);
        }

        private long calculateDelay() {
            var random = ThreadLocalRandom.current();
            var delay = baseLatencyMs;
            delay += calculateJitter(random);
            delay += calculateSpike(random);
            return delay;
        }

        private long calculateJitter(ThreadLocalRandom random) {
            return jitterMs > 0
                   ? random.nextLong(jitterMs)
                   : 0;
        }

        private long calculateSpike(ThreadLocalRandom random) {
            return spikeChance > 0 && random.nextDouble() < spikeChance
                   ? spikeLatencyMs
                   : 0;
        }

        private static Promise<Unit> scheduleDelay(long delayMs) {
            return promise(p -> SCHEDULER.schedule(() -> p.succeed(unit()), delayMs, TimeUnit.MILLISECONDS));
        }

        public static Result<LatencySimulation> latencySimulation(long baseMs,
                                                                  long jitterMs,
                                                                  double spikeChance,
                                                                  long spikeMs) {
            return validateTimings(baseMs, jitterMs, spikeMs).flatMap(_ -> Verify.ensure(spikeChance,
                                                                                         Verify.Is::between,
                                                                                         0.0,
                                                                                         1.0,
                                                                                         SPIKE_CHANCE_OUT_OF_RANGE))
                                  .map(_ -> new LatencySimulation(baseMs, jitterMs, spikeChance, spikeMs));
        }

        private static Result<Long> validateTimings(long baseMs, long jitterMs, long spikeMs) {
            return Verify.ensure(baseMs, Verify.Is::nonNegative, BASE_LATENCY_NEGATIVE)
                         .flatMap(_ -> Verify.ensure(jitterMs, Verify.Is::nonNegative, JITTER_NEGATIVE))
                         .flatMap(_ -> Verify.ensure(spikeMs, Verify.Is::nonNegative, SPIKE_LATENCY_NEGATIVE));
        }

        public static LatencySimulation latencySimulation(long latencyMs) {
            return latencySimulation(latencyMs, 0, 0, 0).unwrap();
        }

        public static LatencySimulation latencySimulation(long baseMs, long jitterMs) {
            return latencySimulation(baseMs, jitterMs, 0, 0).unwrap();
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
            return Promise.success(unit());
        }

        private SimulatedError selectRandomError(ThreadLocalRandom random) {
            return errorTypes.get(random.nextInt(errorTypes.size()));
        }

        public static Result<FailureInjection> failureInjection(double rate, List<SimulatedError> errors) {
            return Verify.ensure(rate, Verify.Is::between, 0.0, 1.0, FAILURE_RATE_OUT_OF_RANGE)
                         .flatMap(_ -> ensureNonEmptyErrors(errors))
                         .map(_ -> new FailureInjection(rate, errors));
        }

        private static Result<List<SimulatedError>> ensureNonEmptyErrors(List<SimulatedError> errors) {
            return Verify.ensure(errors, Verify.Is::notNull, ERROR_TYPES_EMPTY)
                         .filter(ERROR_TYPES_EMPTY,
                                 list -> !list.isEmpty());
        }

        public static Result<FailureInjection> failureInjection(double rate, SimulatedError... errors) {
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
            var result = Promise.success(unit());
            for (var simulation : simulations) {
                result = result.flatMap(_ -> simulation.apply());
            }
            return result;
        }

        public static Result<Composite> composite(List<BackendSimulation> simulations) {
            return Verify.ensure(simulations, Verify.Is::notNull, SIMULATIONS_EMPTY)
                         .filter(SIMULATIONS_EMPTY,
                                 list -> !list.isEmpty())
                         .map(Composite::new);
        }

        public static Result<Composite> composite(BackendSimulation... simulations) {
            return composite(List.of(simulations));
        }
    }

    /// Simulated error types for failure injection.
    sealed interface SimulatedError extends Cause {
        record ServiceUnavailable(String serviceName) implements SimulatedError {
            public static Result<ServiceUnavailable> serviceUnavailable(String serviceName) {
                return success(new ServiceUnavailable(serviceName));
            }

            @Override
            public String message() {
                return "Service unavailable: " + serviceName;
            }
        }

        record Timeout(String operation, long timeoutMs) implements SimulatedError {
            public static Result<Timeout> timeout(String operation, long timeoutMs) {
                return success(new Timeout(operation, timeoutMs));
            }

            @Override
            public String message() {
                return "Timeout after " + timeoutMs + "ms: " + operation;
            }
        }

        record ConnectionRefused(String host, int port) implements SimulatedError {
            public static Result<ConnectionRefused> connectionRefused(String host, int port) {
                return success(new ConnectionRefused(host, port));
            }

            @Override
            public String message() {
                return "Connection refused: " + host + ":" + port;
            }
        }

        record DatabaseError(String query) implements SimulatedError {
            public static Result<DatabaseError> databaseError(String query) {
                return success(new DatabaseError(query));
            }

            @Override
            public String message() {
                return "Database error executing: " + query;
            }
        }

        record RateLimited(int retryAfterSeconds) implements SimulatedError {
            public static Result<RateLimited> rateLimited(int retryAfterSeconds) {
                return success(new RateLimited(retryAfterSeconds));
            }

            @Override
            public String message() {
                return "Rate limited, retry after " + retryAfterSeconds + " seconds";
            }
        }

        record CustomError(String errorType, String errorMessage) implements SimulatedError {
            public static Result<CustomError> customError(String errorType, String errorMessage) {
                return success(new CustomError(errorType, errorMessage));
            }

            @Override
            public String message() {
                return errorType + ": " + errorMessage;
            }
        }

        record unused() implements SimulatedError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    record unused() implements BackendSimulation {
        @Override
        public Promise<Unit> apply() {
            return Promise.success(unit());
        }
    }
}
