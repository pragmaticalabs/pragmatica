package org.pragmatica.aether.forge;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * JSON request/response records for Forge API.
 * Used with JsonMapper for serialization/deserialization.
 */
public final class ForgeResponses {
    private ForgeResponses() {}

    // ========== Common Responses ==========
    public record SuccessResponse(boolean success) {
        public static final SuccessResponse OK = new SuccessResponse(true);
    }

    public record ErrorResponse(boolean success, String error) {
        public static ErrorResponse error(String message) {
            return new ErrorResponse(false, message);
        }
    }

    // ========== Event Records ==========
    public record ForgeEvent(String timestamp, String type, String message) {}

    // ========== Load Requests ==========
    public record SetLoadRequest(int rate) {}

    public record RampLoadRequest(int targetRate, long durationMs) {}

    public record RampLoadResponse(boolean success, int targetRate, long durationMs) {}

    // ========== Chaos Requests ==========
    public record ChaosInjectRequest(String type,
                                     Option<String> nodeId,
                                     Option<Integer> durationMs,
                                     Option<String> sliceArtifact,
                                     Option<Double> failureRate,
                                     Option<Integer> latencyMs) {
        // JSON deserializers (Jackson, Gson, etc.) set missing fields to null.
        // This compact constructor converts null to Option.none() at the boundary.
        public ChaosInjectRequest {
            nodeId = nodeId != null
                     ? nodeId
                     : Option.none();
            durationMs = durationMs != null
                         ? durationMs
                         : Option.none();
            sliceArtifact = sliceArtifact != null
                            ? sliceArtifact
                            : Option.none();
            failureRate = failureRate != null
                          ? failureRate
                          : Option.none();
            latencyMs = latencyMs != null
                        ? latencyMs
                        : Option.none();
        }
    }

    public record ChaosInjectResponse(boolean success, String eventId, String message) {}

    // ========== Deploy Requests ==========
    public record DeployRequest(String artifact, int instances) {
        public DeployRequest {
            if (instances <= 0) instances = 1;
        }
    }

    public record ScaleRequest(String artifact, int instances) {}

    public record UndeployRequest(String artifact) {}

    // ========== Status Responses ==========
    public record NodeMetricsResponse(String nodeId,
                                      boolean isLeader,
                                      double cpuUsage,
                                      long heapUsedMb,
                                      long heapMaxMb) {}

    public record LoadConfigResponse(int targetCount,
                                     int totalRps,
                                     List<LoadTargetInfo> targets) {}

    public record LoadTargetInfo(String name,
                                 String target,
                                 String rate,
                                 String duration) {}

    public record LoadStatusResponse(boolean running,
                                     List<TargetStatusInfo> targets) {}

    public record TargetStatusInfo(String name,
                                   String target,
                                   int currentRate,
                                   long totalRequests,
                                   long successCount,
                                   long failureCount,
                                   double avgLatencyMs,
                                   String remainingDuration) {}

    // ========== Simulator Config ==========
    public record SimulatorConfigRequest(Option<String> mode,
                                         Option<Double> baseLatencyMs,
                                         Option<Double> latencyVariance,
                                         Option<Double> errorRate,
                                         Option<Double> timeoutRate) {
        // JSON deserializers (Jackson, Gson, etc.) set missing fields to null.
        // This compact constructor converts null to Option.none() at the boundary.
        public SimulatorConfigRequest {
            mode = mode != null
                   ? mode
                   : Option.none();
            baseLatencyMs = baseLatencyMs != null
                            ? baseLatencyMs
                            : Option.none();
            latencyVariance = latencyVariance != null
                              ? latencyVariance
                              : Option.none();
            errorRate = errorRate != null
                        ? errorRate
                        : Option.none();
            timeoutRate = timeoutRate != null
                          ? timeoutRate
                          : Option.none();
        }
    }

    // ========== Entry Points ==========
    public record EntryPointInfo(String name, int rate) {}

    public record EntryPointsResponse(List<EntryPointInfo> entryPoints) {}

    // ========== Load Runner Status ==========
    public record LoadRunnerStatusResponse(String state,
                                           int targetCount,
                                           List<LoadRunnerTargetInfo> targets) {}

    public record LoadRunnerTargetInfo(String name,
                                       int targetRate,
                                       int actualRate,
                                       long requests,
                                       long success,
                                       long failures,
                                       double avgLatencyMs,
                                       double successRate,
                                       Option<String> remaining) {
        // JSON deserializers (Jackson, Gson, etc.) set missing fields to null.
        // This compact constructor converts null to Option.none() at the boundary.
        public LoadRunnerTargetInfo {
            remaining = remaining != null
                        ? remaining
                        : Option.none();
        }
    }

    // ========== Full Status Response ==========
    public record FullStatusResponse(ClusterInfo cluster,
                                     MetricsInfo metrics,
                                     LoadInfo load,
                                     long uptimeSeconds,
                                     int sliceCount) {}

    public record ClusterInfo(List<NodeInfo> nodes,
                              String leaderId,
                              int nodeCount) {}

    public record NodeInfo(String id,
                           int port,
                           String state,
                           boolean isLeader) {}

    public record MetricsInfo(double requestsPerSecond,
                              double successRate,
                              double avgLatencyMs,
                              long totalSuccess,
                              long totalFailures) {}

    public record LoadInfo(int currentRate,
                           int targetRate,
                           boolean running) {}

    // ========== Action Responses ==========
    public record NodeActionResponse(boolean success, String newLeader) {}

    public record NodeAddedResponse(boolean success, String nodeId, String state) {}

    public record RateSetResponse(boolean success, int newRate) {}

    public record ChaosEnabledResponse(boolean success, boolean enabled) {}

    public record ChaosStoppedResponse(boolean success, String eventId) {}
}
