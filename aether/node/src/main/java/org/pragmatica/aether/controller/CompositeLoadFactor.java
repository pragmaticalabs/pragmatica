package org.pragmatica.aether.controller;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Computes a weighted composite load factor from multiple scaling metrics.
 *
 * <p>The Lizard Brain scaling algorithm uses relative change detection:
 * <ul>
 *   <li>Each metric maintains a sliding window of recent values</li>
 *   <li>Relative change = current / rolling average</li>
 *   <li>Composite score = sum of (weight * relative change) for each metric</li>
 * </ul>
 *
 * <p>Scaling decisions are only made when windows are full, providing
 * natural warm-up protection without explicit timers.
 */
public interface CompositeLoadFactor {
    /**
     * Compute composite score with given current values.
     * This allows computing the score with fresh samples before recording them.
     *
     * @param currentValues Map of metric to current value
     * @return LoadFactorResult with composite score
     */
    LoadFactorResult computeWithCurrentValues(Map<ScalingMetric, Double> currentValues);

    /**
     * Result of computing the composite load factor.
     *
     * @param compositeScore Weighted sum of relative changes (1.0 = at average)
     * @param canScale       True if enough windows are full to make scaling decisions
     * @param components     Per-metric relative change values
     */
    record LoadFactorResult(double compositeScore,
                            boolean canScale,
                            Map<ScalingMetric, Double> components) {
        /**
         * Factory method following JBCT naming convention.
         */
        public static LoadFactorResult loadFactorResult(double compositeScore,
                                                        boolean canScale,
                                                        Map<ScalingMetric, Double> components) {
            return new LoadFactorResult(compositeScore, canScale, Map.copyOf(components));
        }

        /**
         * Create a result indicating scaling is blocked (not enough data).
         */
        public static LoadFactorResult notReady() {
            return new LoadFactorResult(1.0, false, Map.of());
        }
    }

    /**
     * Compute the current composite load factor.
     *
     * @return LoadFactorResult with composite score and per-metric components
     */
    LoadFactorResult compute();

    /**
     * Record a metric sample.
     *
     * @param metric Metric type
     * @param value  Current metric value
     */
    void recordSample(ScalingMetric metric, double value);

    /**
     * Check if error rate exceeds the block threshold.
     * This is a guard rail that blocks scale-up when errors are high.
     *
     * @return True if error rate is above the blocking threshold
     */
    boolean isErrorRateHigh();

    /**
     * Create a new CompositeLoadFactor with the given configuration.
     *
     * @param config Scaling configuration
     * @return New CompositeLoadFactor instance
     */
    static CompositeLoadFactor compositeLoadFactor(ScalingConfig config) {
        return new CompositeLoadFactorImpl(config);
    }
}

/**
 * Implementation of CompositeLoadFactor using MetricWindow for each metric.
 *
 * <p>Thread-safety note: This implementation uses synchronized methods rather than
 * ConcurrentHashMap or other lock-free structures. This design choice is intentional:
 * <ul>
 *   <li>Metrics sampling occurs infrequently (typically every 5 seconds via ControlLoop)</li>
 *   <li>Contention is minimal - usually a single thread (MetricsCollector) writes samples</li>
 *   <li>The compute() operation reads multiple related values that should be consistent</li>
 *   <li>Synchronized blocks are short (simple map operations and arithmetic)</li>
 * </ul>
 * Lock-free structures would add complexity without measurable performance benefit
 * given the low-frequency access pattern.
 */
class CompositeLoadFactorImpl implements CompositeLoadFactor {
    private final ScalingConfig config;
    private final Map<ScalingMetric, MetricWindow> windows;

    CompositeLoadFactorImpl(ScalingConfig config) {
        this.config = config;
        this.windows = new EnumMap<>(ScalingMetric.class);
        // Initialize windows for all metrics
        for (ScalingMetric metric : ScalingMetric.values()) {
            windows.put(metric,
                        MetricWindow.metricWindow(config.windowSize()));
        }
    }

    @Override
    public synchronized LoadFactorResult compute() {
        return computeCompositeScore(this::relativeChangeFromWindow);
    }

    /**
     * Compute relative change using last value from window (for compute()).
     */
    private double relativeChangeFromWindow(ScalingMetric metric) {
        var window = windows.get(metric);
        var average = window.average();
        var current = window.lastValue();
        return average > 0
               ? current / average
               : 1.0;
    }

    /**
     * Check if we have enough data in windows with non-zero weights.
     * Returns false if no metrics have positive weights (prevents allMatch on empty stream returning true).
     */
    private boolean hasEnoughWeightedData() {
        var weightedEntries = windows.entrySet()
                                     .stream()
                                     .filter(this::hasPositiveWeight)
                                     .toList();
        // No metrics with positive weights means we can't make scaling decisions
        if (weightedEntries.isEmpty()) {
            return false;
        }
        return weightedEntries.stream()
                              .allMatch(this::isWindowFull);
    }

    private boolean hasPositiveWeight(Map.Entry<ScalingMetric, MetricWindow> entry) {
        return config.weight(entry.getKey()) > 0;
    }

    private boolean isWindowFull(Map.Entry<ScalingMetric, MetricWindow> entry) {
        return entry.getValue()
                    .isFull();
    }

    @Override
    public synchronized void recordSample(ScalingMetric metric, double value) {
        var oldWindow = windows.get(metric);
        var newWindow = oldWindow.record(value);
        windows.put(metric, newWindow);
    }

    /**
     * Compute relative change for a metric given a current value.
     * This is used when we have a fresh sample to compare against the window.
     *
     * @param metric  The metric
     * @param current Current value
     * @return Relative change (current / average)
     */
    public synchronized double computeRelativeChange(ScalingMetric metric, double current) {
        return windows.get(metric)
                      .relativeChange(current);
    }

    @Override
    public synchronized LoadFactorResult computeWithCurrentValues(Map<ScalingMetric, Double> currentValues) {
        return computeCompositeScore(metric -> relativeChangeFromCurrentValues(metric, currentValues));
    }

    /**
     * Compute relative change using provided current values (for computeWithCurrentValues()).
     */
    private double relativeChangeFromCurrentValues(ScalingMetric metric, Map<ScalingMetric, Double> currentValues) {
        var current = currentValues.getOrDefault(metric, 0.0);
        return windows.get(metric)
                      .relativeChange(current);
    }

    /**
     * Shared computation logic for both compute() and computeWithCurrentValues().
     * Weight normalization: The composite score is normalized by total weight to make it
     * comparable to thresholds regardless of how many metrics are enabled.
     *
     * @param relativeChangeProvider Function that provides relative change for each metric
     * @return LoadFactorResult with composite score and components
     */
    private LoadFactorResult computeCompositeScore(Function<ScalingMetric, Double> relativeChangeProvider) {
        if (!hasEnoughWeightedData()) {
            return LoadFactorResult.notReady();
        }
        var components = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        var compositeScore = 0.0;
        var totalWeight = 0.0;
        for (ScalingMetric metric : ScalingMetric.values()) {
            var weight = config.weight(metric);
            if (weight <= 0) {
                continue;
            }
            var relativeChange = relativeChangeProvider.apply(metric);
            components.put(metric, relativeChange);
            compositeScore += weight * relativeChange;
            totalWeight += weight;
        }
        // When totalWeight is 0 (all weights disabled), return neutral score (1.0)
        // This prevents erroneous scale-down decisions
        if (totalWeight <= 0) {
            return LoadFactorResult.loadFactorResult(1.0, true, components);
        }
        // Normalize composite score by total weight
        compositeScore = compositeScore / totalWeight;
        return LoadFactorResult.loadFactorResult(compositeScore, true, components);
    }

    @Override
    public synchronized boolean isErrorRateHigh() {
        var errorWindow = windows.get(ScalingMetric.ERROR_RATE);
        if (!errorWindow.isFull()) {
            return false;
        }
        return errorWindow.average() > config.errorRateBlockThreshold();
    }

    /**
     * Get the current configuration.
     */
    public synchronized ScalingConfig config() {
        return config;
    }

    /**
     * Check if a specific metric's window is full.
     */
    public synchronized boolean isWindowFull(ScalingMetric metric) {
        return windows.get(metric)
                      .isFull();
    }
}
