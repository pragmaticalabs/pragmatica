package org.pragmatica.aether.ttm;

import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;
import org.pragmatica.lang.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/// DecisionTreeController enhanced with TTM predictions.
///
/// Adjusts thresholds based on TTM forecasts for proactive scaling.
/// This creates a two-tier control system:
///
///   - Tier 1: Decision tree (reactive, 1-second evaluations)
///   - Tier 2: TTM predictions (proactive, 1-minute evaluations)
///
public interface AdaptiveDecisionTree extends ClusterController {
    /// Get the underlying decision tree controller.
    ClusterController baseController();

    /// Get the TTM manager.
    TTMManager ttmManager();

    /// Get current effective configuration (with TTM adjustments).
    ControllerConfig effectiveConfig();

    /// Create adaptive controller.
    static AdaptiveDecisionTree adaptiveDecisionTree(DecisionTreeController baseController, TTMManager ttmManager) {
        record adaptiveDecisionTree(DecisionTreeController baseController, TTMManager ttmManager) implements
                                                                                                  AdaptiveDecisionTree {
            private static final Logger log = LoggerFactory.getLogger(adaptiveDecisionTree.class);

            adaptiveDecisionTree(DecisionTreeController baseController, TTMManager ttmManager) {
                this.baseController = baseController;
                this.ttmManager = ttmManager;
                // Register for forecast updates to adjust thresholds
                ttmManager.onForecast(this::onForecast);
            }

            @Override
            public ControllerConfig effectiveConfig() {
                return baseController.configuration();
            }

            @Override
            public Promise<ControlDecisions> evaluate(ControlContext context) {
                var preemptiveChanges = ttmManager.currentForecast()
                                                  .filter(this::meetsConfidenceThreshold)
                                                  .map(forecast -> getPreemptiveChanges(forecast, context))
                                                  .or(List.of());
                return baseController.evaluate(context)
                                     .map(decisions -> mergeDecisions(preemptiveChanges, decisions));
            }

            private boolean meetsConfidenceThreshold(TTMForecast forecast) {
                return forecast.confidence() > ttmManager.config()
                                                         .confidenceThreshold();
            }

            private ControlDecisions mergeDecisions(List<BlueprintChange> preemptiveChanges,
                                                    ControlDecisions decisions) {
                if (preemptiveChanges.isEmpty()) {
                    return decisions;
                }
                var merged = new ArrayList<>(preemptiveChanges);
                merged.addAll(decisions.changes());
                return new ControlDecisions(merged);
            }

            private void onForecast(TTMForecast forecast) {
                switch (forecast.recommendation()) {
                    case ScalingRecommendation.AdjustThresholds adjust -> {
                        var current = baseController.configuration();
                        var updated = current.withCpuScaleUpThreshold(adjust.newCpuScaleUpThreshold())
                                             .withCpuScaleDownThreshold(adjust.newCpuScaleDownThreshold());
                        log.info("TTM adjusting thresholds: scaleUp={} -> {}, scaleDown={} -> {}",
                                 current.cpuScaleUpThreshold(),
                                 adjust.newCpuScaleUpThreshold(),
                                 current.cpuScaleDownThreshold(),
                                 adjust.newCpuScaleDownThreshold());
                        baseController.updateConfiguration(updated);
                    }
                    case ScalingRecommendation.PreemptiveScaleUp scaleUp -> log.info(
                            "TTM recommends preemptive scale up: predictedCpu={}, instances={}",
                            scaleUp.predictedCpuPeak(),
                            scaleUp.suggestedInstances());
                    case ScalingRecommendation.PreemptiveScaleDown scaleDown -> log.info(
                            "TTM recommends preemptive scale down: predictedCpu={}, instances={}",
                            scaleDown.predictedCpuTrough(),
                            scaleDown.suggestedInstances());
                    case ScalingRecommendation.NoAction _ -> {
                    }
                }
            }

            /// Generate preemptive scaling changes based on TTM forecast.
            ///
            /// Current implementation applies scaling to the first scalable blueprint.
            /// Future enhancement: distribute scaling across blueprints based on load contribution.
            private List<BlueprintChange> getPreemptiveChanges(TTMForecast forecast, ControlContext context) {
                return switch (forecast.recommendation()) {
                    case ScalingRecommendation.PreemptiveScaleUp scaleUp -> {
                        // Find first blueprint with room to scale up
                        var candidate = context.blueprints()
                                               .values()
                                               .stream()
                                               .filter(b -> b.instances() > 0)
                                               .findFirst();
                        if (candidate.isEmpty()) {
                            yield List.of();
                        }
                        var blueprint = candidate.get();
                        int additional = Math.max(1,
                                                  scaleUp.suggestedInstances() - blueprint.instances());
                        if (additional <= 0) {
                            yield List.of();
                        }
                        log.debug("Preemptive scale up: {} +{} instances (predicted CPU peak: {})",
                                  blueprint.artifact(),
                                  additional,
                                  scaleUp.predictedCpuPeak());
                        yield List.of(new BlueprintChange.ScaleUp(blueprint.artifact(), additional));
                    }
                    case ScalingRecommendation.PreemptiveScaleDown scaleDown -> {
                        // Find first blueprint with room to scale down
                        var candidate = context.blueprints()
                                               .values()
                                               .stream()
                                               .filter(b -> b.instances() > 1)
                                               .findFirst();
                        if (candidate.isEmpty()) {
                            yield List.of();
                        }
                        var blueprint = candidate.get();
                        int reduction = Math.min(blueprint.instances() - 1,
                                                 Math.max(1,
                                                          blueprint.instances() - scaleDown.suggestedInstances()));
                        if (reduction <= 0) {
                            yield List.of();
                        }
                        log.debug("Preemptive scale down: {} -{} instances (predicted CPU trough: {})",
                                  blueprint.artifact(),
                                  reduction,
                                  scaleDown.predictedCpuTrough());
                        yield List.of(new BlueprintChange.ScaleDown(blueprint.artifact(), reduction));
                    }
                    case ScalingRecommendation.AdjustThresholds _, ScalingRecommendation.NoAction _ -> List.of();
                };
            }
        }

        return new adaptiveDecisionTree(baseController, ttmManager);
    }
}

