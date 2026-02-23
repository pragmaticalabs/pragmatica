package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// Simple rule-based controller for MVP.
///
///
/// Rules (evaluated in order):
/// <ol>
///   - IF avg(cpu) > 0.8 → scale up by 1
///   - IF avg(cpu) < 0.2 AND instances > 1 → scale down by 1
///   - IF method call rate > threshold → scale up
/// </ol>
///
///
/// Future versions will support configurable rules via YAML.
public interface DecisionTreeController extends ClusterController {
    /// Create a decision tree controller with default thresholds.
    static DecisionTreeController decisionTreeController() {
        return decisionTreeController(ControllerConfig.DEFAULT);
    }

    /// Create a decision tree controller with custom thresholds.
    ///
    /// @return Result containing controller or validation error
    static Result<DecisionTreeController> decisionTreeController(double cpuScaleUpThreshold,
                                                                 double cpuScaleDownThreshold,
                                                                 double callRateScaleUpThreshold) {
        return ControllerConfig.controllerConfig(cpuScaleUpThreshold,
                                                 cpuScaleDownThreshold,
                                                 callRateScaleUpThreshold,
                                                 1000)
                               .map(config -> decisionTreeController(config));
    }

    /// Create a decision tree controller with full configuration.
    static DecisionTreeController decisionTreeController(ControllerConfig config) {
        return new ControllerState(config, new ConcurrentHashMap<>(), System.currentTimeMillis());
    }

    /// Get current configuration.
    ControllerConfig configuration();

    /// Update configuration at runtime.
    Unit updateConfiguration(ControllerConfig config);

    /// Internal mutable state holder for the controller.
    /// Uses a class instead of record to support volatile config field.
    final class ControllerState implements DecisionTreeController {
        private static final Logger log = LoggerFactory.getLogger(DecisionTreeController.class);

        private volatile ControllerConfig config;
        private final Map<String, Double> previousCallCounts;
        private final AtomicLong lastEvaluationTime;

        ControllerState(ControllerConfig config,
                        Map<String, Double> previousCallCounts,
                        long lastEvaluationTime) {
            this.config = config;
            this.previousCallCounts = previousCallCounts;
            this.lastEvaluationTime = new AtomicLong(lastEvaluationTime);
        }

        @Override
        public ControllerConfig configuration() {
            return config;
        }

        @Override
        public Unit updateConfiguration(ControllerConfig config) {
            log.info("Controller configuration updated: {}", config);
            this.config = config;
            return unit();
        }

        @Override
        public Promise<ControlDecisions> evaluate(ControlContext context) {
            var currentConfig = this.config;
            var avgCpu = context.avgMetric(MetricsCollector.CPU_USAGE);
            log.debug("Evaluating: avgCpu={}, blueprints={}",
                      avgCpu,
                      context.blueprints()
                             .size());
            // Compute elapsed time atomically using CAS loop to prevent race
            // where two concurrent calls both get near-zero elapsed time
            var currentTime = System.currentTimeMillis();
            long previousTime;
            long expected;
            do{
                expected = lastEvaluationTime.get();
                previousTime = expected;
            } while (!lastEvaluationTime.compareAndSet(expected, currentTime));
            var elapsedSeconds = Math.max(1.0, (currentTime - previousTime) / 1000.0);
            // Prune previousCallCounts at the start of each evaluation cycle.
            // Metrics are rebuilt from context.metrics() each cycle, so stale entries
            // (from metrics no longer reported by any node) are removed.
            var currentMetricKeys = context.metrics()
                                           .values()
                                           .stream()
                                           .flatMap(nodeMetrics -> nodeMetrics.keySet()
                                                                              .stream())
                                           .filter(this::isCallMetric)
                                           .collect(java.util.stream.Collectors.toSet());
            previousCallCounts.keySet()
                              .retainAll(currentMetricKeys);
            var changes = context.blueprints()
                                 .entrySet()
                                 .stream()
                                 .map(entry -> evaluateBlueprint(entry.getKey(),
                                                                 entry.getValue(),
                                                                 avgCpu,
                                                                 context.metrics(),
                                                                 currentConfig,
                                                                 elapsedSeconds))
                                 .flatMap(List::stream)
                                 .toList();
            return Promise.success(new ControlDecisions(changes));
        }

        private List<BlueprintChange> evaluateBlueprint(Artifact artifact,
                                                        Blueprint blueprint,
                                                        double avgCpu,
                                                        Map<NodeId, Map<String, Double>> metrics,
                                                        ControllerConfig currentConfig,
                                                        double elapsedSeconds) {
            return evaluateCpuRules(artifact, blueprint, avgCpu, currentConfig).orElse(() -> evaluateCallRateRule(artifact,
                                                                                                                  metrics,
                                                                                                                  currentConfig,
                                                                                                                  elapsedSeconds))
                                   .or(List::of);
        }

        private Option<List<BlueprintChange>> evaluateCpuRules(Artifact artifact,
                                                               Blueprint blueprint,
                                                               double avgCpu,
                                                               ControllerConfig currentConfig) {
            // Rule 1: High CPU → scale up
            if (avgCpu > currentConfig.cpuScaleUpThreshold()) {
                log.info("Rule triggered: High CPU ({} > {}), scaling up {}",
                         avgCpu,
                         currentConfig.cpuScaleUpThreshold(),
                         artifact);
                return Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
            }
            // Rule 2: Low CPU → scale down (if above minimum instances)
            if (avgCpu < currentConfig.cpuScaleDownThreshold() && blueprint.instances() > blueprint.minInstances()) {
                log.info("Rule triggered: Low CPU ({} < {}), scaling down {}",
                         avgCpu,
                         currentConfig.cpuScaleDownThreshold(),
                         artifact);
                return Option.some(List.of(new BlueprintChange.ScaleDown(artifact, 1)));
            }
            return Option.none();
        }

        private Option<List<BlueprintChange>> evaluateCallRateRule(Artifact artifact,
                                                                   Map<NodeId, Map<String, Double>> metrics,
                                                                   ControllerConfig currentConfig,
                                                                   double elapsedSeconds) {
            // Rule 3: High call rate → scale up
            // Collect call metrics and update previous counts, then check for high rate
            var callMetricEntries = metrics.values()
                                           .stream()
                                           .flatMap(nodeMetrics -> nodeMetrics.entrySet()
                                                                              .stream())
                                           .filter(entry -> isCallMetric(entry.getKey()))
                                           .toList();
            var hasHighCallRate = checkAndUpdateCallRates(callMetricEntries, elapsedSeconds, currentConfig);
            if (hasHighCallRate) {
                log.info("Rule triggered: High call rate, scaling up {}", artifact);
                return Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
            }
            return Option.none();
        }

        /// Check for high call rates and update previous call counts atomically.
        /// Uses ConcurrentHashMap.put() which atomically returns the previous value,
        /// eliminating the non-atomic getOrDefault/put race.
        private boolean checkAndUpdateCallRates(List<Map.Entry<String, Double>> callMetricEntries,
                                                double elapsedSeconds,
                                                ControllerConfig currentConfig) {
            var hasHighRate = false;
            for (var entry : callMetricEntries) {
                var metricName = entry.getKey();
                var currentCount = entry.getValue();
                // put() atomically stores new value and returns previous (or null if absent)
                var previous = previousCallCounts.put(metricName, currentCount);
                var previousCount = previous != null
                                    ? previous
                                    : 0.0;
                var delta = currentCount - previousCount;
                var callsPerSecond = delta / elapsedSeconds;
                if (callsPerSecond > currentConfig.callRateScaleUpThreshold()) {
                    hasHighRate = true;
                }
            }
            return hasHighRate;
        }

        private boolean isCallMetric(String metricName) {
            return metricName.startsWith("method.") && metricName.endsWith(".calls");
        }
    }
}
