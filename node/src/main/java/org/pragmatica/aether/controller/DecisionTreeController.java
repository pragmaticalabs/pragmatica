package org.pragmatica.aether.controller;

import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return decisionTreeController(
        0.8, 0.2, 1000);
    }

    /**
     * Create a decision tree controller with custom thresholds.
     */
    static DecisionTreeController decisionTreeController(double cpuScaleUpThreshold,
                                                         double cpuScaleDownThreshold,
                                                         double callRateScaleUpThreshold) {
        return new DecisionTreeControllerImpl(
        cpuScaleUpThreshold, cpuScaleDownThreshold, callRateScaleUpThreshold);
    }
}

class DecisionTreeControllerImpl implements DecisionTreeController {
    private static final Logger log = LoggerFactory.getLogger(DecisionTreeControllerImpl.class);

    private final double cpuScaleUpThreshold;
    private final double cpuScaleDownThreshold;
    private final double callRateScaleUpThreshold;

    DecisionTreeControllerImpl(double cpuScaleUpThreshold,
                               double cpuScaleDownThreshold,
                               double callRateScaleUpThreshold) {
        this.cpuScaleUpThreshold = cpuScaleUpThreshold;
        this.cpuScaleDownThreshold = cpuScaleDownThreshold;
        this.callRateScaleUpThreshold = callRateScaleUpThreshold;
    }

    @Override
    public Promise<ControlDecisions> evaluate(ControlContext context) {
        var changes = new ArrayList<BlueprintChange>();
        // Get current CPU usage
        var avgCpu = context.avgMetric(MetricsCollector.CPU_USAGE);
        log.debug("Evaluating: avgCpu={}, blueprints={}",
                  avgCpu,
                  context.blueprints()
                         .size());
        for (var entry : context.blueprints()
                                .entrySet()) {
            var artifact = entry.getKey();
            var blueprint = entry.getValue();
            // Rule 1: High CPU → scale up
            if (avgCpu > cpuScaleUpThreshold) {
                log.info("Rule triggered: High CPU ({} > {}), scaling up {}", avgCpu, cpuScaleUpThreshold, artifact);
                changes.add(new BlueprintChange.ScaleUp(artifact, 1));
                continue;
            }
            // Rule 2: Low CPU → scale down (if more than 1 instance)
            if (avgCpu < cpuScaleDownThreshold && blueprint.instances() > 1) {
                log.info("Rule triggered: Low CPU ({} < {}), scaling down {}", avgCpu, cpuScaleDownThreshold, artifact);
                changes.add(new BlueprintChange.ScaleDown(artifact, 1));
                continue;
            }
            // Rule 3: High call rate → scale up
            // Check all methods for this artifact
            for (var metricsEntry : context.metrics()
                                           .entrySet()) {
                var nodeMetrics = metricsEntry.getValue();
                for (var metricName : nodeMetrics.keySet()) {
                    if (metricName.startsWith("method.") && metricName.endsWith(".calls")) {
                        var callCount = nodeMetrics.get(metricName);
                        if (callCount != null && callCount > callRateScaleUpThreshold) {
                            log.info("Rule triggered: High call rate ({} > {}), scaling up {}",
                                     callCount,
                                     callRateScaleUpThreshold,
                                     artifact);
                            changes.add(new BlueprintChange.ScaleUp(artifact, 1));
                            break;
                        }
                    }
                }
            }
        }
        return Promise.success(new ControlDecisions(changes));
    }
}
