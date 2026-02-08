package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.ForgeMetrics.MetricsSnapshot;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.time.Instant;
import java.util.Deque;
import java.util.List;

import static org.pragmatica.aether.forge.api.ForgeApiResponses.*;

/**
 * Status-related routes for the Forge API.
 * Provides endpoints for cluster status, node metrics, events, and health checks.
 */
public final class StatusRoutes {
    private StatusRoutes() {}

    /**
     * Create status routes with the given dependencies.
     *
     * @param cluster   the Forge cluster instance
     * @param metrics   the Forge metrics instance
     * @param events    the event log deque
     * @param startTime the server start time in milliseconds
     * @param loadRunner the ConfigurableLoadRunner for load testing
     * @return RouteSource containing all status-related routes
     */
    public static RouteSource statusRoutes(ForgeCluster cluster,
                                           ForgeMetrics metrics,
                                           Deque<ForgeEvent> events,
                                           long startTime,
                                           ConfigurableLoadRunner loadRunner) {
        return RouteSource.of(statusRoute(cluster, metrics, startTime, loadRunner),
                              nodeMetricsRoute(cluster),
                              eventsRoute(events),
                              healthRoute());
    }

    private static Route<FullStatusResponse> statusRoute(ForgeCluster cluster,
                                                         ForgeMetrics metrics,
                                                         long startTime,
                                                         ConfigurableLoadRunner loadRunner) {
        return Route.<FullStatusResponse> get("/api/status")
                    .toJson(() -> buildFullStatus(cluster, metrics, startTime, loadRunner));
    }

    private static Route<List<NodeMetricsResponse>> nodeMetricsRoute(ForgeCluster cluster) {
        return Route.<List<NodeMetricsResponse>> get("/api/node-metrics")
                    .toJson(() -> buildNodeMetrics(cluster));
    }

    private static Route<List<ForgeEvent>> eventsRoute(Deque<ForgeEvent> events) {
        return Route.<List<ForgeEvent>> get("/api/events")
                    .toJson(() -> buildEventsList(events));
    }

    private static Route<HealthResponse> healthRoute() {
        return Route.<HealthResponse> get("/health")
                    .toJson(StatusRoutes::buildHealthResponse);
    }

    // ==================== Handler Methods ====================
    public static FullStatusResponse buildFullStatus(ForgeCluster cluster,
                                                      ForgeMetrics metrics,
                                                      long startTime,
                                                      ConfigurableLoadRunner loadRunner) {
        var clusterStatus = cluster.status();
        var metricsSnapshot = metrics.currentMetrics();
        var nodeInfos = buildNodeInfos(clusterStatus);
        var clusterInfo = new ClusterInfo(nodeInfos, clusterStatus.leaderId(), nodeInfos.size());
        var metricsInfo = buildMetricsInfo(metricsSnapshot);
        var loadInfo = buildLoadInfo(loadRunner);
        var uptimeSeconds = uptimeSeconds(startTime);
        var sliceCount = countSlices(cluster);
        var targetClusterSize = cluster.effectiveClusterSize();
        var nodeMetrics = buildNodeMetrics(cluster);
        var slices = buildSliceStatusInfos(cluster);
        var loadTargets = buildLoadTargets(loadRunner);
        return new FullStatusResponse(clusterInfo, metricsInfo, loadInfo, uptimeSeconds, sliceCount,
                                      targetClusterSize, nodeMetrics, slices, loadTargets);
    }

    private static List<NodeInfo> buildNodeInfos(ForgeCluster.ClusterStatus clusterStatus) {
        return clusterStatus.nodes()
                            .stream()
                            .map(n -> new NodeInfo(n.id(),
                                                   n.port(),
                                                   n.state(),
                                                   n.isLeader()))
                            .toList();
    }

    private static MetricsInfo buildMetricsInfo(MetricsSnapshot snapshot) {
        return new MetricsInfo(snapshot.requestsPerSecond(),
                               snapshot.successRate(),
                               snapshot.avgLatencyMs(),
                               snapshot.totalSuccess(),
                               snapshot.totalFailures());
    }

    private static LoadInfo buildLoadInfo(ConfigurableLoadRunner loadRunner) {
        return new LoadInfo(loadRunner.state().name(),
                            loadRunner.config().totalRequestsPerSecond(),
                            loadRunner.config().targets().size());
    }

    private static int countSlices(ForgeCluster cluster) {
        return cluster.allNodes()
                      .stream()
                      .mapToInt(node -> node.sliceStore()
                                            .loaded()
                                            .size())
                      .sum();
    }

    private static long uptimeSeconds(long startTime) {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    private static List<NodeMetricsResponse> buildNodeMetrics(ForgeCluster cluster) {
        return cluster.nodeMetrics()
                      .stream()
                      .map(m -> new NodeMetricsResponse(m.nodeId(),
                                                        m.isLeader(),
                                                        m.cpuUsage(),
                                                        m.heapUsedMb(),
                                                        m.heapMaxMb()))
                      .toList();
    }

    private static List<SliceStatusInfo> buildSliceStatusInfos(ForgeCluster cluster) {
        return cluster.slicesStatus()
                      .stream()
                      .map(s -> new SliceStatusInfo(s.artifact(),
                                                    s.state(),
                                                    s.instances()
                                                     .stream()
                                                     .map(i -> new SliceInstanceInfo(i.nodeId(), i.state()))
                                                     .toList()))
                      .toList();
    }

    private static List<LoadRunnerTargetInfo> buildLoadTargets(ConfigurableLoadRunner loadRunner) {
        return loadRunner.allTargetMetrics()
                         .values()
                         .stream()
                         .map(t -> new LoadRunnerTargetInfo(t.name(),
                                                            t.targetRate(),
                                                            t.actualRate(),
                                                            t.totalRequests(),
                                                            t.successCount(),
                                                            t.failureCount(),
                                                            t.avgLatencyMs(),
                                                            t.successRate(),
                                                            t.remainingDuration()
                                                             .map(Object::toString)))
                         .toList();
    }

    private static List<ForgeEvent> buildEventsList(Deque<ForgeEvent> events) {
        return events.stream()
                     .map(e -> new ForgeEvent(e.timestamp(),
                                              e.type(),
                                              e.message()))
                     .toList();
    }

    private static HealthResponse buildHealthResponse() {
        return new HealthResponse("healthy",
                                  Instant.now()
                                         .toString());
    }
}
