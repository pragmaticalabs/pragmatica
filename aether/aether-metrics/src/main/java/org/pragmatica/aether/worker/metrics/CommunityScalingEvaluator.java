package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/// Evaluates community metrics to detect scaling needs.
/// Maintains a sliding window of aggregated metrics and checks thresholds.
/// Only emits a CommunityScalingRequest when sustained threshold breaches are detected.
///
/// Lifecycle: created when node becomes governor, destroyed on demotion.
@SuppressWarnings({"JBCT-RET-01", "JBCT-ZONE-02"}) public sealed interface CommunityScalingEvaluator permits ActiveCommunityScalingEvaluator {
    double DEFAULT_SCALE_UP_CPU_THRESHOLD = 0.80;

    double DEFAULT_SCALE_DOWN_CPU_THRESHOLD = 0.20;

    double DEFAULT_SCALE_UP_P95_THRESHOLD_MS = 500.0;

    double DEFAULT_SCALE_UP_ERROR_RATE_THRESHOLD = 0.10;

    long DEFAULT_COOLDOWN_MS = 60_000;

    int DEFAULT_WINDOW_SIZE = 5;

    int DEFAULT_SUSTAINED_COUNT = 3;

    Option<CommunityScalingRequest> evaluate(String communityId,
                                             NodeId governorId,
                                             int memberCount,
                                             WindowSample currentSample);
    List<WindowSample> slidingWindow();
    void reset();

    static CommunityScalingEvaluator communityScalingEvaluator() {
        return communityScalingEvaluator(DEFAULT_SCALE_UP_CPU_THRESHOLD,
                                         DEFAULT_SCALE_DOWN_CPU_THRESHOLD,
                                         DEFAULT_SCALE_UP_P95_THRESHOLD_MS,
                                         DEFAULT_SCALE_UP_ERROR_RATE_THRESHOLD,
                                         DEFAULT_COOLDOWN_MS,
                                         DEFAULT_WINDOW_SIZE,
                                         DEFAULT_SUSTAINED_COUNT);
    }

    static CommunityScalingEvaluator communityScalingEvaluator(double scaleUpCpuThreshold,
                                                               double scaleDownCpuThreshold,
                                                               double scaleUpP95ThresholdMs,
                                                               double scaleUpErrorRateThreshold,
                                                               long cooldownMs,
                                                               int windowSize,
                                                               int sustainedCount) {
        return new ActiveCommunityScalingEvaluator(scaleUpCpuThreshold,
                                                   scaleDownCpuThreshold,
                                                   scaleUpP95ThresholdMs,
                                                   scaleUpErrorRateThreshold,
                                                   cooldownMs,
                                                   windowSize,
                                                   sustainedCount,
                                                   new ArrayDeque<>(windowSize),
                                                   new ConcurrentHashMap<>());
    }
}
