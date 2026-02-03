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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Simple rule-based controller for MVP.
 *
 * <p>Rules (evaluated in order):
 * <ol>
 *   <li>IF avg(cpu) > 0.8 → scale up by 1</li>
 *   <li>IF avg(cpu) < 0.2 AND instances > 1 → scale down by 1</li>
 *   <li>IF method call rate > threshold → scale up</li>
 * </ol>
 *
 * <p>Future versions will support configurable rules via YAML.
 */
public interface DecisionTreeController extends ClusterController {
    /**
     * Create a decision tree controller with default thresholds.
     */
    static DecisionTreeController decisionTreeController() {
        return decisionTreeController(ControllerConfig.DEFAULT);
    }

    /**
     * Create a decision tree controller with custom thresholds.
     *
     * @return Result containing controller or validation error
     */
    static Result<DecisionTreeController> decisionTreeController(double cpuScaleUpThreshold,
                                                                 double cpuScaleDownThreshold,
                                                                 double callRateScaleUpThreshold) {
        return ControllerConfig.controllerConfig(cpuScaleUpThreshold,
                                                 cpuScaleDownThreshold,
                                                 callRateScaleUpThreshold,
                                                 1000)
                               .map(DecisionTreeControllerImpl::new);
    }

    /**
     * Create a decision tree controller with full configuration.
     */
    static DecisionTreeController decisionTreeController(ControllerConfig config) {
        return new DecisionTreeControllerImpl(config);
    }

    /**
     * Get current configuration.
     */
    ControllerConfig configuration();

    /**
     * Update configuration at runtime.
     */
    Unit updateConfiguration(ControllerConfig config);
}

class DecisionTreeControllerImpl implements DecisionTreeController {
    private static final Logger log = LoggerFactory.getLogger(DecisionTreeControllerImpl.class);

    private volatile ControllerConfig config;

    // Track previous call counts for rate calculation
    private final Map<String, Double> previousCallCounts = new ConcurrentHashMap<>();
    private volatile long lastEvaluationTime = System.currentTimeMillis();

    DecisionTreeControllerImpl(ControllerConfig config) {
        this.config = config;
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
        var changes = context.blueprints()
                             .entrySet()
                             .stream()
                             .map(entry -> evaluateBlueprint(entry.getKey(),
                                                             entry.getValue(),
                                                             avgCpu,
                                                             context.metrics(),
                                                             currentConfig))
                             .flatMap(List::stream)
                             .toList();
        return Promise.success(new ControlDecisions(changes));
    }

    private List<BlueprintChange> evaluateBlueprint(Artifact artifact,
                                                    Blueprint blueprint,
                                                    double avgCpu,
                                                    Map<NodeId, Map<String, Double>> metrics,
                                                    ControllerConfig currentConfig) {
        return evaluateCpuRules(artifact, blueprint, avgCpu, currentConfig).orElse(() -> evaluateCallRateRule(artifact,
                                                                                                              metrics,
                                                                                                              currentConfig))
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
        // Rule 2: Low CPU → scale down (if more than 1 instance)
        if (avgCpu < currentConfig.cpuScaleDownThreshold() && blueprint.instances() > 1) {
            log.info("Rule triggered: Low CPU ({} < {}), scaling down {}",
                     avgCpu,
                     currentConfig.cpuScaleDownThreshold(),
                     artifact);
            return Option.some(List.of(new BlueprintChange.ScaleDown(artifact, 1)));
        }
        return Option.empty();
    }

    private Option<List<BlueprintChange>> evaluateCallRateRule(Artifact artifact,
                                                               Map<NodeId, Map<String, Double>> metrics,
                                                               ControllerConfig currentConfig) {
        // Rule 3: High call rate → scale up
        // Calculate actual rate using delta from previous evaluation
        var currentTime = System.currentTimeMillis();
        var elapsedSeconds = Math.max(1.0, (currentTime - lastEvaluationTime) / 1000.0);
        lastEvaluationTime = currentTime;
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
        return Option.empty();
    }

    /**
     * Check for high call rates and update previous call counts.
     * Separates the query (checking rates) from the mutation (updating counts).
     */
    private boolean checkAndUpdateCallRates(List<Map.Entry<String, Double>> callMetricEntries,
                                            double elapsedSeconds,
                                            ControllerConfig currentConfig) {
        var hasHighRate = false;
        for (var entry : callMetricEntries) {
            var metricName = entry.getKey();
            var currentCount = entry.getValue();
            var previousCount = previousCallCounts.getOrDefault(metricName, 0.0);
            var delta = currentCount - previousCount;
            var callsPerSecond = delta / elapsedSeconds;
            // Update count after computing rate
            previousCallCounts.put(metricName, currentCount);
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
