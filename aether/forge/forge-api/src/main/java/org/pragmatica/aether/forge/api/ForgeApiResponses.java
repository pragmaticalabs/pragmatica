package org.pragmatica.aether.forge.api;

import org.pragmatica.lang.Option;

import java.time.Duration;
import java.util.List;


/// API response records for Forge endpoints.
/// Organized by domain for clarity and discoverability.
public final class ForgeApiResponses {
    private ForgeApiResponses() {}

    static String formatDuration(Duration duration) {
        var totalSeconds = duration.toSeconds();
        var hours = totalSeconds / 3600;
        var minutes = (totalSeconds % 3600) / 60;
        var seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public record SuccessResponse(boolean success) {
        public static final SuccessResponse OK = new SuccessResponse(true);
    }

    public record ErrorResponse(boolean success, String error) {
        public static ErrorResponse error(String message) {
            return new ErrorResponse(false, message);
        }
    }

    public record FullStatusResponse(ClusterInfo cluster,
                                     MetricsInfo metrics,
                                     AetherAggregates aetherMetrics,
                                     LoadInfo load,
                                     long uptimeSeconds,
                                     int sliceCount,
                                     int targetClusterSize,
                                     List<NodeMetricsResponse> nodeMetrics,
                                     List<SliceStatusInfo> slices,
                                     List<LoadRunnerTargetInfo> loadTargets,
                                     List<InvocationInfo> invocations){}

    public record InvocationInfo(String artifact,
                                 String method,
                                 long count,
                                 long successCount,
                                 long failureCount,
                                 double avgDurationMs,
                                 double errorRate){}

    public record ClusterInfo(List<NodeInfo> nodes, String leaderId, int nodeCount){}

    public record NodeInfo(String id, int port, String state, boolean isLeader){}

    public record SliceStatusInfo(String artifact, String state, List<SliceInstanceInfo> instances){}

    public record SliceInstanceInfo(String nodeId, String state){}

    public record MetricsInfo(double requestsPerSecond,
                              double successRate,
                              double avgLatencyMs,
                              long totalSuccess,
                              long totalFailures){}

    public record AetherAggregates(double rps,
                                   double successRate,
                                   double avgLatencyMs,
                                   long totalInvocations,
                                   long totalSuccess,
                                   long totalFailures){}

    public record LoadInfo(String state, int totalTargetRate, int targetCount){}

    public record NodeMetricsResponse(String nodeId,
                                      boolean isLeader,
                                      double cpuUsage,
                                      long heapUsedMb,
                                      long heapMaxMb){}

    public record HealthResponse(String status, String timestamp){}

    public record ChaosStatusResponse(boolean enabled, int activeEventCount, List<ActiveChaosEventInfo> activeEvents){}

    public record ActiveChaosEventInfo(String eventId,
                                       String type,
                                       String description,
                                       String startedAt,
                                       String duration){}

    public record ChaosInjectResponse(boolean success, String eventId, String type){}

    public record ChaosEnabledResponse(boolean success, boolean enabled){}

    public record ChaosStoppedResponse(boolean success, String eventId){}

    public record NodeActionResponse(boolean success, String newLeader){}

    public record NodeAddedResponse(boolean success, String nodeId, String state){}

    public record LoadConfigResponse(int targetCount, int totalRps, List<LoadTargetInfo> targets){}

    public record LoadTargetInfo(String name, String target, String rate, String duration){}

    public record LoadConfigUploadResponse(boolean success, int targetCount, int totalRps){}

    public record LoadRunnerStatusResponse(String state, int targetCount, List<LoadRunnerTargetInfo> targets){}

    public record LoadRunnerTargetInfo(String name,
                                       int targetRate,
                                       int actualRate,
                                       long requests,
                                       long success,
                                       long failures,
                                       double avgLatencyMs,
                                       double successRate,
                                       Option<String> remaining) {
        public LoadRunnerTargetInfo {
            remaining = remaining != null
                       ? remaining
                       : Option.none();
        }
    }

    public record LoadControlResponse(boolean success, String state){}

    public record RateSetResponse(boolean success, int newRate){}

    public record InventoryModeResponse(String mode){}

    public record InventoryModeSetResponse(boolean success, String mode){}

    public record InventoryMetricsResponse(long totalReservations,
                                           long totalReleases,
                                           long stockOuts,
                                           boolean infiniteMode,
                                           int refillRate){}

    public record SimulatorModeInfo(String name, String displayName, String description, boolean chaosEnabled){}

    public record ModeChangeResponse(boolean success, String previousMode, String currentMode){}

    public record PlaceOrderResponse(boolean success, String orderId, String status, String total){}

    public record OrderStatusResponse(boolean success, String orderId, String status, String total, int itemCount){}

    public record CancelOrderResponse(boolean success, String orderId, String status, String reason){}

    public record CheckStockResponse(boolean success, String productId, int available, boolean sufficient){}

    public record GetPriceResponse(boolean success, String productId, String price){}

    public record RepositoryPutResponse(boolean success, String path, int size){}

    public record ForgeEvent(String timestamp, String type, String severity, String message){}

    public record MultiplierSetResponse(boolean success, double multiplier){}

    public record TopologyResponse(List<TopologyNodeInfo> nodes, List<TopologyEdgeInfo> edges){}

    public record TopologyNodeInfo(String id, String type, String label, String sliceArtifact){}

    public record TopologyEdgeInfo(String from, String to, String style, String topicConfig){}

    public record ForgeStatusResponse(boolean forge){}
}
