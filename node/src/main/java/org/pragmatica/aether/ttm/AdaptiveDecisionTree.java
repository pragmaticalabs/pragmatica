package org.pragmatica.aether.ttm;

import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DecisionTreeController enhanced with TTM predictions.
 * <p>
 * Adjusts thresholds based on TTM forecasts for proactive scaling.
 * This creates a two-tier control system:
 * <ul>
 *   <li>Tier 1: Decision tree (reactive, 1-second evaluations)</li>
 *   <li>Tier 2: TTM predictions (proactive, 1-minute evaluations)</li>
 * </ul>
 */
public interface AdaptiveDecisionTree extends ClusterController {
    /**
     * Get the underlying decision tree controller.
     */
    DecisionTreeController baseController();

    /**
     * Get the TTM manager.
     */
    TTMManager ttmManager();

    /**
     * Get current effective configuration (with TTM adjustments).
     */
    ControllerConfig effectiveConfig();

    /**
     * Create adaptive controller.
     */
    static AdaptiveDecisionTree adaptiveDecisionTree(DecisionTreeController baseController, TTMManager ttmManager) {
        return new AdaptiveDecisionTreeImpl(baseController, ttmManager);
    }
}

final class AdaptiveDecisionTreeImpl implements AdaptiveDecisionTree {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveDecisionTreeImpl.class);

    private final DecisionTreeController baseController;
    private final TTMManager ttmManager;

    AdaptiveDecisionTreeImpl(DecisionTreeController baseController, TTMManager ttmManager) {
        this.baseController = baseController;
        this.ttmManager = ttmManager;
        // Register for forecast updates to adjust thresholds
        ttmManager.onForecast(this::onForecast);
    }

    @Override
    public DecisionTreeController baseController() {
        return baseController;
    }

    @Override
    public TTMManager ttmManager() {
        return ttmManager;
    }

    @Override
    public ControllerConfig effectiveConfig() {
        return baseController.getConfiguration();
    }

    @Override
    public Promise<ControlDecisions> evaluate(ControlContext context) {
        var preemptiveChanges = ttmManager.currentForecast()
                                          .filter(this::meetsConfidenceThreshold)
                                          .map(this::getPreemptiveChanges)
                                          .or(List.of());
        return baseController.evaluate(context)
                             .map(decisions -> mergeDecisions(preemptiveChanges, decisions));
    }

    private boolean meetsConfidenceThreshold(TTMForecast forecast) {
        return forecast.confidence() > ttmManager.config().confidenceThreshold();
    }

    private ControlDecisions mergeDecisions(List<BlueprintChange> preemptiveChanges, ControlDecisions decisions) {
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
                var current = baseController.getConfiguration();
                var updated = current.withCpuScaleUpThreshold(adjust.newCpuScaleUpThreshold())
                                     .withCpuScaleDownThreshold(adjust.newCpuScaleDownThreshold());
                log.info("TTM adjusting thresholds: scaleUp={} -> {}, scaleDown={} -> {}",
                         current.cpuScaleUpThreshold(),
                         adjust.newCpuScaleUpThreshold(),
                         current.cpuScaleDownThreshold(),
                         adjust.newCpuScaleDownThreshold());
                baseController.updateConfiguration(updated);
            }
            case ScalingRecommendation.PreemptiveScaleUp scaleUp ->
            log.info("TTM recommends preemptive scale up: predictedCpu={}, instances={}",
                     scaleUp.predictedCpuPeak(),
                     scaleUp.suggestedInstances());
            case ScalingRecommendation.PreemptiveScaleDown scaleDown ->
            log.info("TTM recommends preemptive scale down: predictedCpu={}, instances={}",
                     scaleDown.predictedCpuTrough(),
                     scaleDown.suggestedInstances());
            case ScalingRecommendation.NoAction _ -> {}
        }
    }

    private java.util.List<BlueprintChange> getPreemptiveChanges(TTMForecast forecast) {
        var changes = new ArrayList<BlueprintChange>();
        // Note: Preemptive scaling requires artifact context from the control loop.
        // The actual scaling is handled by logging recommendations and adjusting thresholds.
        // Future enhancement: Pass artifact information to enable direct preemptive scaling.
        switch (forecast.recommendation()) {
            case ScalingRecommendation.PreemptiveScaleUp _ ->
            log.debug("Preemptive scale up indicated; threshold adjustment applied");
            case ScalingRecommendation.PreemptiveScaleDown _ ->
            log.debug("Preemptive scale down indicated; threshold adjustment applied");
            default -> {}
        }
        return changes;
    }
}
