package org.pragmatica.aether.controller;

import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;

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
    static org.pragmatica.lang.Result<DecisionTreeController> decisionTreeController(double cpuScaleUpThreshold,
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
    ControllerConfig getConfiguration();

    /**
     * Update configuration at runtime.
     */
    Unit updateConfiguration(ControllerConfig config);
}

class DecisionTreeControllerImpl implements DecisionTreeController {
    private static final Logger log = LoggerFactory.getLogger(DecisionTreeControllerImpl.class);

    private volatile ControllerConfig config;

    DecisionTreeControllerImpl(ControllerConfig config) {
        this.config = config;
    }

    @Override
    public ControllerConfig getConfiguration() {
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

    private List<BlueprintChange> evaluateBlueprint(org.pragmatica.aether.artifact.Artifact artifact,
                                                    Blueprint blueprint,
                                                    double avgCpu,
                                                    java.util.Map<org.pragmatica.consensus.NodeId, java.util.Map<String, Double>> metrics,
                                                    ControllerConfig currentConfig) {
        return evaluateCpuRules(artifact, blueprint, avgCpu, currentConfig)
                               .orElse(() -> evaluateCallRateRule(artifact, metrics, currentConfig))
                               .or(List::of);
    }

    private org.pragmatica.lang.Option<List<BlueprintChange>> evaluateCpuRules(org.pragmatica.aether.artifact.Artifact artifact,
                                                                               Blueprint blueprint,
                                                                               double avgCpu,
                                                                               ControllerConfig currentConfig) {
        // Rule 1: High CPU → scale up
        if (avgCpu > currentConfig.cpuScaleUpThreshold()) {
            log.info("Rule triggered: High CPU ({} > {}), scaling up {}",
                     avgCpu,
                     currentConfig.cpuScaleUpThreshold(),
                     artifact);
            return org.pragmatica.lang.Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
        }
        // Rule 2: Low CPU → scale down (if more than 1 instance)
        if (avgCpu < currentConfig.cpuScaleDownThreshold() && blueprint.instances() > 1) {
            log.info("Rule triggered: Low CPU ({} < {}), scaling down {}",
                     avgCpu,
                     currentConfig.cpuScaleDownThreshold(),
                     artifact);
            return org.pragmatica.lang.Option.some(List.of(new BlueprintChange.ScaleDown(artifact, 1)));
        }
        return org.pragmatica.lang.Option.empty();
    }

    private org.pragmatica.lang.Option<List<BlueprintChange>> evaluateCallRateRule(org.pragmatica.aether.artifact.Artifact artifact,
                                                                                   java.util.Map<org.pragmatica.consensus.NodeId, java.util.Map<String, Double>> metrics,
                                                                                   ControllerConfig currentConfig) {
        // Rule 3: High call rate → scale up
        var hasHighCallRate = metrics.values()
                                     .stream()
                                     .flatMap(nodeMetrics -> nodeMetrics.entrySet()
                                                                        .stream())
                                     .filter(entry -> isCallMetric(entry.getKey()))
                                     .anyMatch(entry -> entry.getValue() > currentConfig.callRateScaleUpThreshold());
        if (hasHighCallRate) {
            log.info("Rule triggered: High call rate, scaling up {}", artifact);
            return org.pragmatica.lang.Option.some(List.of(new BlueprintChange.ScaleUp(artifact, 1)));
        }
        return org.pragmatica.lang.Option.empty();
    }

    private boolean isCallMetric(String metricName) {
        return metricName.startsWith("method.") && metricName.endsWith(".calls");
    }
}
