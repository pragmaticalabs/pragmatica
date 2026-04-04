package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;


/// Active implementation of CommunityScalingEvaluator.
@SuppressWarnings({"JBCT-STY-05", "JBCT-RET-01", "JBCT-ZONE-02"}) final class ActiveCommunityScalingEvaluator implements CommunityScalingEvaluator {
    private static final Logger log = LoggerFactory.getLogger(CommunityScalingEvaluator.class);

    private static final String SCALE_UP = "UP";

    private static final String SCALE_DOWN = "DOWN";

    private final double scaleUpCpuThreshold;
    private final double scaleDownCpuThreshold;
    private final double scaleUpP95ThresholdMs;
    private final double scaleUpErrorRateThreshold;
    private final long cooldownMs;
    private final int windowSize;
    private final int sustainedCount;
    private final Deque<WindowSample> window;
    private final ConcurrentHashMap<String, Long> lastScalingRequestTime;

    ActiveCommunityScalingEvaluator(double scaleUpCpuThreshold,
                                    double scaleDownCpuThreshold,
                                    double scaleUpP95ThresholdMs,
                                    double scaleUpErrorRateThreshold,
                                    long cooldownMs,
                                    int windowSize,
                                    int sustainedCount,
                                    Deque<WindowSample> window,
                                    ConcurrentHashMap<String, Long> lastScalingRequestTime) {
        this.scaleUpCpuThreshold = scaleUpCpuThreshold;
        this.scaleDownCpuThreshold = scaleDownCpuThreshold;
        this.scaleUpP95ThresholdMs = scaleUpP95ThresholdMs;
        this.scaleUpErrorRateThreshold = scaleUpErrorRateThreshold;
        this.cooldownMs = cooldownMs;
        this.windowSize = windowSize;
        this.sustainedCount = sustainedCount;
        this.window = window;
        this.lastScalingRequestTime = lastScalingRequestTime;
    }

    @Override public Option<CommunityScalingRequest> evaluate(String communityId,
                                                              NodeId governorId,
                                                              int memberCount,
                                                              WindowSample currentSample) {
        addSample(currentSample);
        if (window.size() <sustainedCount) {
            log.trace("Window not full enough ({}/{}), skipping evaluation", window.size(), sustainedCount);
            return empty();
        }
        return detectScaleUp(communityId, governorId, memberCount).orElse(() -> detectScaleDown(communityId,
                                                                                                governorId,
                                                                                                memberCount));
    }

    @Override public List<WindowSample> slidingWindow() {
        return List.copyOf(window);
    }

    @Override public void reset() {
        window.clear();
        lastScalingRequestTime.clear();
    }

    private void addSample(WindowSample sample) {
        window.addLast(sample);
        while (window.size() > windowSize) {window.removeFirst();}
    }

    private Option<CommunityScalingRequest> detectScaleUp(String communityId, NodeId governorId, int memberCount) {
        var cpuBreaches = countCpuBreachesUp();
        var p95Breaches = countP95Breaches();
        var errorBreaches = countErrorBreaches();
        if (cpuBreaches >= sustainedCount || p95Breaches >= sustainedCount || errorBreaches >= sustainedCount) {return emitIfNotCoolingDown(communityId,
                                                                                                                                            governorId,
                                                                                                                                            SCALE_UP,
                                                                                                                                            memberCount,
                                                                                                                                            memberCount + 1);}
        return empty();
    }

    private Option<CommunityScalingRequest> detectScaleDown(String communityId, NodeId governorId, int memberCount) {
        if (countCpuBreachesDown() >= sustainedCount) {return emitIfNotCoolingDown(communityId,
                                                                                   governorId,
                                                                                   SCALE_DOWN,
                                                                                   memberCount,
                                                                                   Math.max(1, memberCount - 1));}
        return empty();
    }

    private long countCpuBreachesUp() {
        return window.stream().filter(s -> s.avgCpuUsage() > scaleUpCpuThreshold)
                            .count();
    }

    private long countCpuBreachesDown() {
        return window.stream().filter(s -> s.avgCpuUsage() <scaleDownCpuThreshold)
                            .count();
    }

    private long countP95Breaches() {
        return window.stream().filter(s -> s.avgP95LatencyMs() > scaleUpP95ThresholdMs)
                            .count();
    }

    private long countErrorBreaches() {
        return window.stream().filter(s -> s.avgErrorRate() > scaleUpErrorRateThreshold)
                            .count();
    }

    private Option<CommunityScalingRequest> emitIfNotCoolingDown(String communityId,
                                                                 NodeId governorId,
                                                                 String direction,
                                                                 int currentInstances,
                                                                 int requestedInstances) {
        var now = System.currentTimeMillis();
        var cooldownKey = communityId + ":" + direction;
        var lastTime = lastScalingRequestTime.get(cooldownKey);
        if (lastTime != null && (now - lastTime) <cooldownMs) {
            log.debug("Scaling {} for {} in cooldown ({} ms remaining)",
                      direction,
                      communityId,
                      cooldownMs - (now - lastTime));
            return empty();
        }
        lastScalingRequestTime.put(cooldownKey, now);
        var evidence = buildEvidence(currentInstances);
        var artifact = buildPlaceholderArtifact(communityId);
        var request = CommunityScalingRequest.communityScalingRequest(communityId,
                                                                      governorId,
                                                                      artifact,
                                                                      direction,
                                                                      currentInstances,
                                                                      requestedInstances,
                                                                      evidence);
        log.info("Emitting {} scaling request for community '{}': {} -> {} instances",
                 direction,
                 communityId,
                 request.currentInstances(),
                 request.requestedInstances());
        return some(request);
    }

    private ScalingEvidence buildEvidence(int memberCount) {
        var avgCpu = window.stream().mapToDouble(WindowSample::avgCpuUsage)
                                  .average()
                                  .orElse(0.0);
        var avgP95 = window.stream().mapToDouble(WindowSample::avgP95LatencyMs)
                                  .average()
                                  .orElse(0.0);
        var totalInvocations = window.stream().mapToLong(WindowSample::totalActiveInvocations)
                                            .sum();
        var avgError = window.stream().mapToDouble(WindowSample::avgErrorRate)
                                    .average()
                                    .orElse(0.0);
        var windowDuration = computeWindowDuration();
        return ScalingEvidence.scalingEvidence(memberCount, avgCpu, avgP95, totalInvocations, avgError, windowDuration);
    }

    private long computeWindowDuration() {
        return Option.all(Option.option(window.peekFirst()),
                          Option.option(window.peekLast())).map((first, last) -> last.timestampMs() - first.timestampMs())
                         .or(0L);
    }

    private static Artifact buildPlaceholderArtifact(String communityId) {
        return Artifact.artifact("org.aether:" + sanitizeArtifactId(communityId) + ":0.0.0").unwrap();
    }

    private static String sanitizeArtifactId(String communityId) {
        return communityId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
