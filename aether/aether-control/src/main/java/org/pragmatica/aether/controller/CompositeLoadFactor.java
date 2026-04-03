package org.pragmatica.aether.controller;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;


/// Computes a weighted composite load factor from multiple scaling metrics.
///
///
/// The Lizard Brain scaling algorithm uses relative change detection:
///
///   - Each metric maintains a sliding window of recent values
///   - Relative change = current / rolling average
///   - Composite score = sum of (weight * relative change) for each metric
///
///
///
/// Scaling decisions are only made when windows are full, providing
/// natural warm-up protection without explicit timers.
@SuppressWarnings("JBCT-RET-01")
// Fire-and-forget metric recording — void is intentional
public interface CompositeLoadFactor {
    LoadFactorResult computeWithCurrentValues(Map<ScalingMetric, Double> currentValues);

    record LoadFactorResult(double compositeScore, boolean canScale, Map<ScalingMetric, Double> components) {
        public static LoadFactorResult loadFactorResult(double compositeScore,
                                                        boolean canScale,
                                                        Map<ScalingMetric, Double> components) {
            return new LoadFactorResult(compositeScore, canScale, Map.copyOf(components));
        }

        public static LoadFactorResult notReady() {
            return new LoadFactorResult(1.0, false, Map.of());
        }
    }

    LoadFactorResult compute();
    void recordSample(ScalingMetric metric, double value);
    boolean isErrorRateHigh();

    static CompositeLoadFactor compositeLoadFactor(ScalingConfig config) {
        var windows = new EnumMap<ScalingMetric, MetricWindow>(ScalingMetric.class);
        for (ScalingMetric metric : ScalingMetric.values()) {MetricWindow.metricWindow(config.windowSize())
                                                                                      .onSuccess(window -> windows.put(metric,
                                                                                                                       window));}
        return new State(config, windows);
    }

    final class State implements CompositeLoadFactor {
        private final ScalingConfig config;
        private final Map<ScalingMetric, MetricWindow> windows;

        State(ScalingConfig config, Map<ScalingMetric, MetricWindow> windows) {
            this.config = config;
            this.windows = windows;
        }

        @Override public synchronized LoadFactorResult compute() {
            return computeCompositeScore(this::relativeChangeFromWindow);
        }

        private double relativeChangeFromWindow(ScalingMetric metric) {
            var window = windows.get(metric);
            var average = window.average();
            var current = window.lastValue();
            return average > 0
                  ? current / average
                  : 1.0;
        }

        private boolean hasEnoughWeightedData() {
            var weightedEntries = windows.entrySet().stream()
                                                  .filter(this::hasPositiveWeight)
                                                  .toList();
            if (weightedEntries.isEmpty()) {return false;}
            return weightedEntries.stream().allMatch(this::isWindowFullEntry);
        }

        private boolean hasPositiveWeight(Map.Entry<ScalingMetric, MetricWindow> entry) {
            return config.weight(entry.getKey()) > 0;
        }

        private boolean isWindowFullEntry(Map.Entry<ScalingMetric, MetricWindow> entry) {
            return entry.getValue().isFull();
        }

        @Override public synchronized void recordSample(ScalingMetric metric, double value) {
            var oldWindow = windows.get(metric);
            var newWindow = oldWindow.record(value);
            windows.put(metric, newWindow);
        }

        public synchronized double computeRelativeChange(ScalingMetric metric, double current) {
            return windows.get(metric).relativeChange(current);
        }

        @Override public synchronized LoadFactorResult computeWithCurrentValues(Map<ScalingMetric, Double> currentValues) {
            return computeCompositeScore(metric -> relativeChangeFromCurrentValues(metric, currentValues));
        }

        private double relativeChangeFromCurrentValues(ScalingMetric metric, Map<ScalingMetric, Double> currentValues) {
            var current = currentValues.getOrDefault(metric, 0.0);
            return windows.get(metric).relativeChange(current);
        }

        private LoadFactorResult computeCompositeScore(Function<ScalingMetric, Double> relativeChangeProvider) {
            if (!hasEnoughWeightedData()) {return LoadFactorResult.notReady();}
            var components = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
            var compositeScore = 0.0;
            var totalWeight = 0.0;
            for (ScalingMetric metric : ScalingMetric.values()) {
                var weight = config.weight(metric);
                if (weight <= 0) {continue;}
                var relativeChange = relativeChangeProvider.apply(metric);
                components.put(metric, relativeChange);
                compositeScore += weight * relativeChange;
                totalWeight += weight;
            }
            if (totalWeight <= 0) {return LoadFactorResult.loadFactorResult(1.0, true, components);}
            compositeScore = compositeScore / totalWeight;
            return LoadFactorResult.loadFactorResult(compositeScore, true, components);
        }

        @Override public synchronized boolean isErrorRateHigh() {
            var errorWindow = windows.get(ScalingMetric.ERROR_RATE);
            if (!errorWindow.isFull()) {return false;}
            return errorWindow.average() > config.errorRateBlockThreshold();
        }

        public synchronized ScalingConfig config() {
            return config;
        }

        public synchronized boolean isWindowFull(ScalingMetric metric) {
            return windows.get(metric).isFull();
        }
    }
}
