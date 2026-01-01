package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
        return new Adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, 3.0);
    }

    /**
     * Adaptive threshold with custom multiplier.
     *
     * @param minThresholdMs Minimum threshold in milliseconds
     * @param maxThresholdMs Maximum threshold in milliseconds
     * @param multiplier     Multiplier for average (e.g., 3.0 means 3x average)
     */
    static ThresholdStrategy adaptive(long minThresholdMs, long maxThresholdMs, double multiplier) {
        return new Adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, multiplier);
    }

    /**
     * Per-method threshold strategy - different thresholds per method.
     *
     * @param defaultThresholdMs Default threshold for methods not explicitly configured
     */
    static PerMethod perMethod(long defaultThresholdMs) {
        return new PerMethod(defaultThresholdMs * 1_000_000);
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
    final class Adaptive implements ThresholdStrategy {
        private final long minThresholdNs;
        private final long maxThresholdNs;
        private final double multiplier;

        // Per-method tracking using exponential moving average
        private final Map<MethodName, MethodStats> methodStats = new ConcurrentHashMap<>();

        Adaptive(long minThresholdNs, long maxThresholdNs, double multiplier) {
            this.minThresholdNs = minThresholdNs;
            this.maxThresholdNs = maxThresholdNs;
            this.multiplier = multiplier;
        }

        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count.get() < 10) {
                // Use minimum threshold until we have enough data
                return durationNs > minThresholdNs;
            }
            return durationNs > computeThreshold(stats);
        }

        @Override
        public void observe(MethodName method, long durationNs) {
            var stats = methodStats.computeIfAbsent(method, _ -> new MethodStats());
            stats.update(durationNs);
        }

        @Override
        public long thresholdNs(MethodName method) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count.get() < 10) {
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
         */
        private static final class MethodStats {
            private static final double ALPHA = 0.1;

            // Smoothing factor
            private final AtomicLong count = new AtomicLong();
            private volatile double ema = 0;

            // Exponential moving average
            void update(long durationNs) {
                var c = count.incrementAndGet();
                if (c == 1) {
                    ema = durationNs;
                }else {
                    // Thread-safe EMA update (approximate, but good enough for thresholds)
                    ema = ALPHA * durationNs + (1 - ALPHA) * ema;
                }
            }

            long averageNs() {
                return (long) ema;
            }
        }
    }

    /**
     * Per-method configurable thresholds.
     */
    final class PerMethod implements ThresholdStrategy {
        private final long defaultThresholdNs;
        private final Map<MethodName, Long> methodThresholds = new ConcurrentHashMap<>();

        PerMethod(long defaultThresholdNs) {
            this.defaultThresholdNs = defaultThresholdNs;
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
    record Composite(
    Map<MethodName, Long> methodThresholdsNs,
    ThresholdStrategy fallback) implements ThresholdStrategy {
        @Override
        public boolean isSlow(MethodName method, long durationNs) {
            var explicit = methodThresholdsNs.get(method);
            if (explicit != null) {
                return durationNs > explicit;
            }
            return fallback.isSlow(method, durationNs);
        }

        @Override
        public void observe(MethodName method, long durationNs) {
            if (!methodThresholdsNs.containsKey(method)) {
                fallback.observe(method, durationNs);
            }
        }

        @Override
        public long thresholdNs(MethodName method) {
            var explicit = methodThresholdsNs.get(method);
            return explicit != null
                   ? explicit
                   : fallback.thresholdNs(method);
        }
    }
}
