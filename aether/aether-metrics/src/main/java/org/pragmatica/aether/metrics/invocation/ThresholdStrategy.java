package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;


/// Strategy for determining when an invocation is considered "slow".
///
/// Slow invocations are captured for debugging, while all invocations
/// contribute to aggregated metrics.
public sealed interface ThresholdStrategy {
    boolean isSlow(MethodName method, long durationNs);

    @Contract default void observe(MethodName method, long durationNs) {}

    long thresholdNs(MethodName method);

    static ThresholdStrategy fixed(long thresholdMs) {
        return new Fixed(thresholdMs * 1_000_000);
    }

    static ThresholdStrategy adaptive(long minThresholdMs, long maxThresholdMs) {
        return Adaptive.adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, 3.0);
    }

    static ThresholdStrategy adaptive(long minThresholdMs, long maxThresholdMs, double multiplier) {
        return Adaptive.adaptive(minThresholdMs * 1_000_000, maxThresholdMs * 1_000_000, multiplier);
    }

    static PerMethod perMethod(long defaultThresholdMs) {
        return PerMethod.perMethod(defaultThresholdMs * 1_000_000);
    }

    static ThresholdStrategy composite(Map<MethodName, Long> methodThresholdsMs, long defaultMinMs, long defaultMaxMs) {
        var adaptive = adaptive(defaultMinMs, defaultMaxMs);
        var methodThresholdsNs = new ConcurrentHashMap<MethodName, Long>();
        methodThresholdsMs.forEach((k, v) -> methodThresholdsNs.put(k, v * 1_000_000));
        return new Composite(methodThresholdsNs, adaptive);
    }

    record unused() implements ThresholdStrategy {
        @Override public boolean isSlow(MethodName method, long durationNs) {
            return false;
        }

        @Override public long thresholdNs(MethodName method) {
            return 0;
        }
    }

    record Fixed(long thresholdNs) implements ThresholdStrategy {
        @Override public boolean isSlow(MethodName method, long durationNs) {
            return durationNs > thresholdNs;
        }

        @Override public long thresholdNs(MethodName method) {
            return thresholdNs;
        }
    }

    record Adaptive(long minThresholdNs,
                    long maxThresholdNs,
                    double multiplier,
                    Map<MethodName, MethodStats> methodStats) implements ThresholdStrategy {
        static Adaptive adaptive(long minThresholdNs, long maxThresholdNs, double multiplier) {
            return new Adaptive(minThresholdNs, maxThresholdNs, multiplier, new ConcurrentHashMap<>());
        }

        @Override public boolean isSlow(MethodName method, long durationNs) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count().get() <10) {return durationNs > minThresholdNs;}
            return durationNs > computeThreshold(stats);
        }

        @Override@Contract public void observe(MethodName method, long durationNs) {
            var stats = methodStats.computeIfAbsent(method, _ -> MethodStats.methodStats());
            stats.update(durationNs);
        }

        @Override public long thresholdNs(MethodName method) {
            var stats = methodStats.get(method);
            if (stats == null || stats.count().get() <10) {return minThresholdNs;}
            return computeThreshold(stats);
        }

        private long computeThreshold(MethodStats stats) {
            var avgNs = stats.averageNs();
            var threshold = (long)(avgNs * multiplier);
            return Math.max(minThresholdNs, Math.min(maxThresholdNs, threshold));
        }

        record MethodStats(AtomicLong count, AtomicReference<Double> ema) {
            private static final double ALPHA = 0.1;

            static MethodStats methodStats() {
                return new MethodStats(new AtomicLong(), new AtomicReference<>(0.0));
            }

            void update(long durationNs) {
                var c = count.incrementAndGet();
                if (c == 1) {ema.set((double) durationNs);} else {updateEma(durationNs);}
            }

            long averageNs() {
                return ema.get().longValue();
            }

            private void updateEma(long durationNs) {
                Double currentEma;
                double newEma;
                do {
                    currentEma = ema.get();
                    newEma = ALPHA * durationNs + (1 - ALPHA) * currentEma;
                } while (!ema.compareAndSet(currentEma, newEma));
            }
        }
    }

    record PerMethod(long defaultThresholdNs, Map<MethodName, Long> methodThresholds) implements ThresholdStrategy {
        static PerMethod perMethod(long defaultThresholdNs) {
            return new PerMethod(defaultThresholdNs, new ConcurrentHashMap<>());
        }

        public PerMethod withThreshold(MethodName method, long thresholdMs) {
            methodThresholds.put(method, thresholdMs * 1_000_000);
            return this;
        }

        public PerMethod removeThreshold(MethodName method) {
            methodThresholds.remove(method);
            return this;
        }

        @Override public boolean isSlow(MethodName method, long durationNs) {
            return durationNs > thresholdNs(method);
        }

        @Override public long thresholdNs(MethodName method) {
            return methodThresholds.getOrDefault(method, defaultThresholdNs);
        }
    }

    record Composite(Map<MethodName, Long> methodThresholdsNs, ThresholdStrategy fallback) implements ThresholdStrategy {
        @Override public boolean isSlow(MethodName method, long durationNs) {
            return option(methodThresholdsNs.get(method)).map(explicit -> durationNs > explicit)
                         .or(() -> fallback.isSlow(method, durationNs));
        }

        @Override@Contract public void observe(MethodName method, long durationNs) {
            if (!methodThresholdsNs.containsKey(method)) {fallback.observe(method, durationNs);}
        }

        @Override public long thresholdNs(MethodName method) {
            return option(methodThresholdsNs.get(method)).or(() -> fallback.thresholdNs(method));
        }
    }
}
