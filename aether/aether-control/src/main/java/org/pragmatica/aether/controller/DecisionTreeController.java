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
    static DecisionTreeController decisionTreeController() {
        return decisionTreeController(ControllerConfig.DEFAULT);
    }

    static Result<DecisionTreeController> decisionTreeController(double cpuScaleUpThreshold,
                                                                 double cpuScaleDownThreshold,
                                                                 double callRateScaleUpThreshold) {
        return ControllerConfig.controllerConfig(cpuScaleUpThreshold,
                                                 cpuScaleDownThreshold,
                                                 callRateScaleUpThreshold,
                                                 1000)
        .map(config -> decisionTreeController(config));
    }

    static DecisionTreeController decisionTreeController(ControllerConfig config) {
        return new ControllerState(config, new ConcurrentHashMap<>(), System.currentTimeMillis());
    }

    ControllerConfig configuration();
    Unit updateConfiguration(ControllerConfig config);

    final class ControllerState implements DecisionTreeController {
        private static final Logger log = LoggerFactory.getLogger(DecisionTreeController.class);

        private volatile ControllerConfig config;
        private final Map<String, Double> previousCallCounts;
        private final AtomicLong lastEvaluationTime;

        ControllerState(ControllerConfig config, Map<String, Double> previousCallCounts, long lastEvaluationTime) {
            this.config = config;
            this.previousCallCounts = previousCallCounts;
            this.lastEvaluationTime = new AtomicLong(lastEvaluationTime);
        }

        @Override public ControllerConfig configuration() {
            return config;
        }

        @Override public Unit updateConfiguration(ControllerConfig config) {
            log.info("Controller configuration updated: {}", config);
            this.config = config;
            return unit();
        }

        @Override public Promise<ControlDecisions> evaluate(ControlContext context) {
            var currentConfig = this.config;
            var avgCpu = context.avgMetric(MetricsCollector.CPU_USAGE);
            log.debug("Evaluating: avgCpu={}, blueprints={}",
                      avgCpu,
                      context.blueprints().size());
            var currentTime = System.currentTimeMillis();
            long previousTime;
            long expected;
            do {
                expected = lastEvaluationTime.get();
                previousTime = expected;
            } while (!lastEvaluationTime.compareAndSet(expected, currentTime));
            var elapsedSeconds = Math.max(1.0, (currentTime - previousTime) / 1000.0);
            var currentMetricKeys = context.metrics().values()
                                                   .stream()
                                                   .flatMap(nodeMetrics -> nodeMetrics.keySet().stream())
                                                   .filter(this::isCallMetric)
                                                   .collect(java.util.stream.Collectors.toSet());
            previousCallCounts.keySet().retainAll(currentMetricKeys);
            var changes = context.blueprints().entrySet()
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
            if (currentConfig.scalingConfig().weights()
                                           .getOrDefault(ScalingMetric.CPU, 0.0) == 0.0) {return Option.none();}
            if (avgCpu > currentConfig.cpuScaleUpThreshold()) {
                log.info("Rule triggered: High CPU ({} > {}), scaling up {}",
                         avgCpu,
                         currentConfig.cpuScaleUpThreshold(),
                         artifact);
                return Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
            }
            if (avgCpu <currentConfig.cpuScaleDownThreshold() && blueprint.instances() > blueprint.minInstances()) {
                log.debug("Rule triggered: Low CPU ({} < {}), scaling down {}",
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
            var callMetricEntries = metrics.values().stream()
                                                  .flatMap(nodeMetrics -> nodeMetrics.entrySet().stream())
                                                  .filter(entry -> isCallMetric(entry.getKey()))
                                                  .toList();
            var hasHighCallRate = checkAndUpdateCallRates(callMetricEntries, elapsedSeconds, currentConfig);
            if (hasHighCallRate) {
                log.info("Rule triggered: High call rate, scaling up {}", artifact);
                return Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
            }
            return Option.none();
        }

        private boolean checkAndUpdateCallRates(List<Map.Entry<String, Double>> callMetricEntries,
                                                double elapsedSeconds,
                                                ControllerConfig currentConfig) {
            var hasHighRate = false;
            for (var entry : callMetricEntries) {
                var metricName = entry.getKey();
                var currentCount = entry.getValue();
                var previous = previousCallCounts.put(metricName, currentCount);
                var previousCount = previous != null
                                   ? previous
                                   : 0.0;
                var delta = currentCount - previousCount;
                var callsPerSecond = delta / elapsedSeconds;
                if (callsPerSecond > currentConfig.callRateScaleUpThreshold()) {hasHighRate = true;}
            }
            return hasHighRate;
        }

        private boolean isCallMetric(String metricName) {
            return metricName.startsWith("method.") && metricName.endsWith(".calls");
        }
    }
}
