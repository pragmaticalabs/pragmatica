package org.pragmatica.aether.forge.api;

import org.pragmatica.lang.Option;

import java.util.List;

/**
 * API response records for Forge endpoints.
 * Organized by domain for clarity and discoverability.
 */
public final class ForgeApiResponses {
    private ForgeApiResponses() {}

    // ========== Common Responses ==========
    /**
     * Generic success response.
     */
    public record SuccessResponse(boolean success) {
        public static final SuccessResponse OK = new SuccessResponse(true);
    }

    /**
     * Generic error response.
     */
    public record ErrorResponse(boolean success, String error) {
        public static ErrorResponse error(String message) {
            return new ErrorResponse(false, message);
        }
    }

    // ========== Status Responses ==========
    /**
     * Full cluster status response from /api/status endpoint.
     */
    public record FullStatusResponse(ClusterInfo cluster,
                                     MetricsInfo metrics,
                                     LoadInfo load,
                                     long uptimeSeconds,
                                     int sliceCount) {}

    /**
     * Cluster information including all nodes.
     */
    public record ClusterInfo(List<NodeInfo> nodes,
                              String leaderId,
                              int nodeCount) {}

    /**
     * Individual node information.
     */
    public record NodeInfo(String id,
                           int port,
                           String state,
                           boolean isLeader) {}

    /**
     * Aggregated metrics information.
     */
    public record MetricsInfo(double requestsPerSecond,
                              double successRate,
                              double avgLatencyMs,
                              long totalSuccess,
                              long totalFailures) {}

    /**
     * Load generator status information.
     */
    public record LoadInfo(int currentRate,
                           int targetRate,
                           boolean running) {}

    /**
     * Per-node metrics response from /api/node-metrics endpoint.
     */
    public record NodeMetricsResponse(String nodeId,
                                      boolean isLeader,
                                      double cpuUsage,
                                      long heapUsedMb,
                                      long heapMaxMb) {}

    /**
     * Health check response from /health endpoint.
     */
    public record HealthResponse(String status, String timestamp) {}

    // ========== Chaos Responses ==========
    /**
     * Chaos controller status from /api/chaos/status endpoint.
     */
    public record ChaosStatusResponse(boolean enabled,
                                      int activeEventCount,
                                      List<ActiveChaosEventInfo> activeEvents) {}

    /**
     * Active chaos event information.
     */
    public record ActiveChaosEventInfo(String eventId,
                                       String type,
                                       String description,
                                       String startedAt,
                                       String duration) {}

    /**
     * Response from /api/chaos/inject endpoint.
     */
    public record ChaosInjectResponse(boolean success,
                                      String eventId,
                                      String type) {}

    /**
     * Response from /api/chaos/enable endpoint.
     */
    public record ChaosEnabledResponse(boolean success, boolean enabled) {}

    /**
     * Response from /api/chaos/stop/{eventId} endpoint.
     */
    public record ChaosStoppedResponse(boolean success, String eventId) {}

    // ========== Node Action Responses ==========
    /**
     * Response from node kill/crash operations.
     */
    public record NodeActionResponse(boolean success, String newLeader) {}

    /**
     * Response from add node operation.
     */
    public record NodeAddedResponse(boolean success, String nodeId, String state) {}

    /**
     * Response from rolling restart operation.
     */
    public record RollingRestartResponse(boolean success) {}

    // ========== Load Responses ==========
    /**
     * Load configuration from /api/load/config GET endpoint.
     */
    public record LoadConfigResponse(int targetCount,
                                     int totalRps,
                                     List<LoadTargetInfo> targets) {}

    /**
     * Individual load target information.
     */
    public record LoadTargetInfo(String name,
                                 String target,
                                 String rate,
                                 String duration) {}

    /**
     * Response from /api/load/config POST endpoint.
     */
    public record LoadConfigUploadResponse(boolean success,
                                           int targetCount,
                                           int totalRps) {}

    /**
     * Load runner status from /api/load/status endpoint.
     */
    public record LoadRunnerStatusResponse(String state,
                                           int targetCount,
                                           List<LoadRunnerTargetInfo> targets) {}

    /**
     * Per-target metrics for load runner.
     */
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

    /**
     * Response from load control operations (start/stop/pause/resume).
     */
    public record LoadControlResponse(boolean success, String state) {}

    /**
     * Response from /api/load/set/{rate} endpoint.
     */
    public record RateSetResponse(boolean success, int newRate) {}

    /**
     * Response from /api/load/ramp endpoint.
     */
    public record RampLoadResponse(boolean success, int targetRate, long durationMs) {}

    // ========== Simulator Responses ==========
    /**
     * Entry point information for simulator.
     */
    public record EntryPointInfo(String name, int rate) {}

    /**
     * Response from /api/simulator/entry-points endpoint.
     */
    public record EntryPointsResponse(List<EntryPointInfo> entryPoints) {}

    /**
     * Response from /api/simulator/rate/{entryPoint} endpoint.
     */
    public record SimulatorRateResponse(boolean success, String entryPoint, int rate) {}

    /**
     * Inventory mode response.
     */
    public record InventoryModeResponse(String mode) {}

    /**
     * Inventory mode set response.
     */
    public record InventoryModeSetResponse(boolean success, String mode) {}

    /**
     * Inventory metrics response.
     */
    public record InventoryMetricsResponse(long totalReservations,
                                           long totalReleases,
                                           long stockOuts,
                                           boolean infiniteMode,
                                           int refillRate) {}

    // ========== Mode Responses ==========
    /**
     * Simulator mode information.
     */
    public record SimulatorModeInfo(String name,
                                    String displayName,
                                    String description,
                                    boolean chaosEnabled) {}

    /**
     * Response from mode change operation.
     */
    public record ModeChangeResponse(boolean success,
                                     String previousMode,
                                     String currentMode) {}

    // ========== Order Simulation Responses ==========
    /**
     * Place order response.
     */
    public record PlaceOrderResponse(boolean success,
                                     String orderId,
                                     String status,
                                     String total) {}

    /**
     * Order status response.
     */
    public record OrderStatusResponse(boolean success,
                                      String orderId,
                                      String status,
                                      String total,
                                      int itemCount) {}

    /**
     * Cancel order response.
     */
    public record CancelOrderResponse(boolean success,
                                      String orderId,
                                      String status,
                                      String reason) {}

    /**
     * Check stock response.
     */
    public record CheckStockResponse(boolean success,
                                     String productId,
                                     int available,
                                     boolean sufficient) {}

    /**
     * Get price response.
     */
    public record GetPriceResponse(boolean success,
                                   String productId,
                                   String price) {}

    // ========== Repository Responses ==========
    /**
     * Repository PUT success response.
     */
    public record RepositoryPutResponse(boolean success,
                                        String path,
                                        int size) {}

    // ========== Event Response ==========
    /**
     * Forge event for event log.
     */
    public record ForgeEvent(String timestamp,
                             String type,
                             String message) {}

    // ========== Multiplier/Config Responses ==========
    /**
     * Response from multiplier set operation.
     */
    public record MultiplierSetResponse(boolean success, double multiplier) {}

    /**
     * Response from load generator enable/disable operation.
     */
    public record LoadGeneratorEnabledResponse(boolean success, boolean enabled) {}
}
