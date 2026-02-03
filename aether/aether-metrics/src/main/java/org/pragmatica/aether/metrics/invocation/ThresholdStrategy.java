package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Strategy for determining when an invocation is considered "slow".
 * <p>
 * Slow invocations are captured for debugging, while all invocations
 * contribute to aggregated metrics.
 */
public sealed interface ThresholdStrategy {
    /**
     * Determine if a duration qualifies as slow for the given method.
     *
     * @param method     The method being invoked
     * @param durationNs Duration in nanoseconds
     * @return true if the invocation should be captured as slow
     */
    boolean isSlow(MethodName method, long durationNs);

    /**
     * Update the strategy with observed data (for adaptive strategies).
     * Called after each invocation.
     *
     * @param method     The method that was invoked
     * @param durationNs Duration in nanoseconds
     */
    default void observe(MethodName method, long durationNs) {}

    /**
     * Get the current threshold for a method (for monitoring).
     *
     * @param method The method
     * @return Threshold in nanoseconds
     */
    long thresholdNs(MethodName method);

    // ========== Factory methods ==========
    /**
     * Fixed threshold strategy - same threshold for all methods.
     *
     * @param thresholdMs Threshold in milliseconds
     */
    static ThresholdStrategy fixed(long thresholdMs) {
        return new Fixed(thresholdMs * 1_000_000);
    }

    /**
     * Adaptive threshold strategy - adjusts based on observed latencies.
     * Captures invocations slower than 3x the rolling average.
     *
     * @param minThresholdMs Minimum threshold in milliseconds (floor)
     * @param maxThresholdMs Maximum threshold in milliseconds (ceiling)
     */
    static ThresholdStrategy adaptive(long minThresholdMs, long maxThresholdMs) {
        return Adaptive.adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, 3.0);
    }

    /**
     * Adaptive threshold with custom multiplier.
     *
     * @param minThresholdMs Minimum threshold in milliseconds
     * @param maxThresholdMs Maximum threshold in milliseconds
     * @param multiplier     Multiplier for average (e.g., 3.0 means 3x average)
     */
    static ThresholdStrategy adaptive(long minThresholdMs, long maxThresholdMs, double multiplier) {
        return Adaptive.adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, multiplier);
    }

    /**
     * Per-method threshold strategy - different thresholds per method.
     *
     * @param defaultThresholdMs Default threshold for methods not explicitly configured
     */
    static PerMethod perMethod(long defaultThresholdMs) {
        return PerMethod.perMethod(defaultThresholdMs * 1_000_000);
    }

    /**
     * Composite strategy - uses per-method thresholds with adaptive fallback.
     */
    static ThresholdStrategy composite(Map<MethodName, Long> methodThresholdsMs,
                                       long defaultMinMs,
                                       long defaultMaxMs) {
        var adaptive = adaptive(defaultMinMs, defaultMaxMs);
        var methodThresholdsNs = new ConcurrentHashMap<MethodName, Long>();
        methodThresholdsMs.forEach((k, v) -> methodThresholdsNs.put(k, v * 1_000_000));
        return new Composite(methodThresholdsNs, adaptive);
    }

    // ========== Implementations ==========
    /**
     * Fixed threshold - same for all methods.
     */
    record Fixed(long thresholdNs) implements ThresholdStrategy {
        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            return durationNs > thresholdNs;
        }

        @Override
        public long thresholdNs(MethodName method) {
            return thresholdNs;
        }
    }

    /**
     * Adaptive threshold - learns from observed latencies.
     */
    record Adaptive(long minThresholdNs,
                    long maxThresholdNs,
                    double multiplier,
                    Map<MethodName, MethodStats> methodStats) implements ThresholdStrategy {
        static Adaptive adaptive(long minThresholdNs, long maxThresholdNs, double multiplier) {
            return new Adaptive(minThresholdNs, maxThresholdNs, multiplier, new ConcurrentHashMap<>());
        }

        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count()
                                      .get() < 10) {
                // Use minimum threshold until we have enough data
                return durationNs > minThresholdNs;
            }
            return durationNs > computeThreshold(stats);
        }

        @Override
        public void observe(MethodName method, long durationNs) {
            var stats = methodStats.computeIfAbsent(method, _ -> MethodStats.methodStats());
            stats.update(durationNs);
        }

        @Override
        public long thresholdNs(MethodName method) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count()
                                      .get() < 10) {
                return minThresholdNs;
            }
            return computeThreshold(stats);
        }

        private long computeThreshold(MethodStats stats) {
            var avgNs = stats.averageNs();
            var threshold = (long)(avgNs * multiplier);
            return Math.max(minThresholdNs, Math.min(maxThresholdNs, threshold));
        }

        /**
         * Per-method statistics using exponential moving average.
         * Thread-safe using CAS loop for atomic EMA updates.
         */
        record MethodStats(AtomicLong count, AtomicReference<Double> ema) {
            private static final double ALPHA = 0.1;

            static MethodStats methodStats() {
                return new MethodStats(new AtomicLong(), new AtomicReference<>(0.0));
            }

            void update(long durationNs) {
                var c = count.incrementAndGet();
                if (c == 1) {
                    ema.set((double) durationNs);
                } else {
                    // Thread-safe EMA update using CAS loop
                    double currentEma;
                    double newEma;
                    do{
                        currentEma = ema.get();
                        newEma = ALPHA * durationNs + (1 - ALPHA) * currentEma;
                    } while (!ema.compareAndSet(currentEma, newEma));
                }
            }

            long averageNs() {
                return ema.get()
                          .longValue();
            }
        }
    }

    /**
     * Per-method configurable thresholds.
     */
    record PerMethod(long defaultThresholdNs,
                     Map<MethodName, Long> methodThresholds) implements ThresholdStrategy {
        static PerMethod perMethod(long defaultThresholdNs) {
            return new PerMethod(defaultThresholdNs, new ConcurrentHashMap<>());
        }

        /**
         * Set threshold for a specific method.
         *
         * @param method      The method
         * @param thresholdMs Threshold in milliseconds
         */
        public PerMethod withThreshold(MethodName method, long thresholdMs) {
            methodThresholds.put(method, thresholdMs * 1_000_000);
            return this;
        }

        /**
         * Remove custom threshold for a method (revert to default).
         */
        public PerMethod removeThreshold(MethodName method) {
            methodThresholds.remove(method);
            return this;
        }

        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            return durationNs > thresholdNs(method);
        }

        @Override
        public long thresholdNs(MethodName method) {
            return methodThresholds.getOrDefault(method, defaultThresholdNs);
        }
    }

    /**
     * Composite strategy - per-method overrides with adaptive fallback.
     */
    record Composite(Map<MethodName, Long> methodThresholdsNs,
                     ThresholdStrategy fallback) implements ThresholdStrategy {
        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            return Option.option(methodThresholdsNs.get(method))
                         .fold(() -> fallback.isSlow(method, durationNs),
                               explicit -> durationNs > explicit);
        }

        @Override
        public void observe(MethodName method, long durationNs) {
            if (!methodThresholdsNs.containsKey(method)) {
                fallback.observe(method, durationNs);
            }
        }

        @Override
        public long thresholdNs(MethodName method) {
            return Option.option(methodThresholdsNs.get(method))
                         .or(() -> fallback.thresholdNs(method));
        }
    }
}
