package org.pragmatica.aether.worker.metrics;

import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Aggregates worker metrics on the governor and feeds the CommunityScalingEvaluator.
/// Created on governor election, destroyed on demotion.
///
/// Cycle:
///   1. Send WorkerMetricsPing to all group followers via NCN
///   2. Collect own CPU/heap via ManagementFactory
///   3. Aggregate stored follower pongs + own metrics
///   4. Feed snapshot to CommunityScalingEvaluator
///   5. If evaluator returns scaling request, send via PassiveNode Broadcast
@SuppressWarnings({"JBCT-RET-01", "JBCT-ZONE-02", "JBCT-ZONE-03"})
public interface WorkerMetricsAggregator {
    Logger LOG = LoggerFactory.getLogger(WorkerMetricsAggregator.class);

    /// Start the aggregation cycle.
    void start();

    /// Stop the aggregation cycle.
    void stop();

    /// Called when a follower sends a metrics pong.
    void onMetricsPong(WorkerMetricsPong pong);

    /// Called when core requests a community metrics snapshot.
    void onSnapshotRequest(CommunityMetricsSnapshotRequest request);

    /// Access the pong store (used by tests).
    ConcurrentHashMap<NodeId, WorkerMetricsPong> pongStore();

    /// Access the scaling evaluator (used by tests).
    CommunityScalingEvaluator evaluator();

    static WorkerMetricsAggregator workerMetricsAggregator(NodeId self,
                                                           DelegateRouter delegateRouter,
                                                           PassiveNode<?, ?> passiveNode,
                                                           Supplier<String> communityIdSupplier,
                                                           Supplier<List<NodeId>> followerSupplier,
                                                           long aggregationIntervalMs) {
        @SuppressWarnings({"JBCT-STY-05", "JBCT-RET-01", "JBCT-ZONE-02", "JBCT-ZONE-03", "JBCT-EX-01"}) record workerMetricsAggregator( NodeId self,
                                                                                                                                        DelegateRouter delegateRouter,
                                                                                                                                        PassiveNode<?, ?> passiveNode,
                                                                                                                                        Supplier<String> communityIdSupplier,
                                                                                                                                        Supplier<List<NodeId>> followerSupplier,
                                                                                                                                        long aggregationIntervalMs,
                                                                                                                                        ConcurrentHashMap<NodeId, WorkerMetricsPong> pongStore,
                                                                                                                                        CommunityScalingEvaluator evaluator,
                                                                                                                                        CancellableTask task) implements WorkerMetricsAggregator {
            private static final int STALE_MULTIPLIER = 2;

            @Override public void start() {
                stop();
                task.set(SharedScheduler.scheduleAtFixedRate(this::runCycle,
                                                             TimeSpan.timeSpan(aggregationIntervalMs).millis()));
                LOG.info("Started metrics aggregator for governor {}", self.id());
            }

            @Override public void stop() {
                task.cancel();
                pongStore.clear();
                evaluator.reset();
                LOG.debug("Stopped metrics aggregator for governor {}", self.id());
            }

            @Override public void onMetricsPong(WorkerMetricsPong pong) {
                pongStore.put(pong.sender(), pong);
            }

            @Override public void onSnapshotRequest(CommunityMetricsSnapshotRequest request) {
                var communityId = communityIdSupplier.get();
                if ( !communityId.equals(request.communityId())) {
                return;}
                var snapshot = buildSnapshot(communityId, request.requestId());
                passiveNode.delegateRouter().route(new NetworkServiceMessage.Broadcast(snapshot));
            }

            private void runCycle() {
                try {
                    sendPingToFollowers();
                    var ownMetrics = collectOwnMetrics();
                    cleanupStalePongs();
                    var sample = aggregateMetrics(ownMetrics);
                    evaluateAndScale(sample);
                }

























                catch (Exception e) {
                    LOG.error("Metrics aggregation cycle error: {}", e.getMessage(), e);
                }
            }

            private void sendPingToFollowers() {
                var ping = WorkerMetricsPing.workerMetricsPing(self);
                followerSupplier.get()
                .forEach(followerId -> delegateRouter.route(new NetworkServiceMessage.Send(followerId, ping)));
            }

            private WorkerMetricsPong collectOwnMetrics() {
                OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
                var cpuLoad = Math.max(0.0,
                                       osBean.getSystemLoadAverage() / Runtime.getRuntime().availableProcessors());
                var heapUsed = memBean.getHeapMemoryUsage().getUsed();
                var heapMax = memBean.getHeapMemoryUsage().getMax();
                var heapUsage = heapMax > 0
                                ? (double) heapUsed / heapMax
                                : 0.0;
                return WorkerMetricsPong.workerMetricsPong(self, cpuLoad, heapUsage, 0L, 0.0, 0.0);
            }

            private void cleanupStalePongs() {
                var cutoff = System.currentTimeMillis() - (STALE_MULTIPLIER * aggregationIntervalMs);
                pongStore.entrySet().removeIf(entry -> entry.getValue().timestampMs() < cutoff);
            }

            private WindowSample aggregateMetrics(WorkerMetricsPong ownMetrics) {
                var allPongs = new ArrayList<>(pongStore.values());
                allPongs.add(ownMetrics);
                return WindowSample.windowSample(allPongs.stream().mapToDouble(WorkerMetricsPong::cpuUsage)
                                                                .average()
                                                                .orElse(0.0),
                                                 allPongs.stream().mapToDouble(WorkerMetricsPong::heapUsage)
                                                                .average()
                                                                .orElse(0.0),
                                                 allPongs.stream().mapToLong(WorkerMetricsPong::activeInvocations)
                                                                .sum(),
                                                 allPongs.stream().mapToDouble(WorkerMetricsPong::p95LatencyMs)
                                                                .average()
                                                                .orElse(0.0),
                                                 allPongs.stream().mapToDouble(WorkerMetricsPong::errorRate)
                                                                .average()
                                                                .orElse(0.0));
            }

            private void evaluateAndScale(WindowSample sample) {
                var communityId = communityIdSupplier.get();
                var memberCount = followerSupplier.get().size() + 1;
                evaluator.evaluate(communityId, self, memberCount, sample).onPresent(this::sendScalingRequest);
            }

            private void sendScalingRequest(CommunityScalingRequest request) {
                LOG.info("Sending {} scaling request for community '{}'", request.direction(), request.communityId());
                passiveNode.delegateRouter().route(new NetworkServiceMessage.Broadcast(request));
            }

            private CommunityMetricsSnapshot buildSnapshot(String communityId, long requestId) {
                return CommunityMetricsSnapshot.communityMetricsSnapshot(communityId,
                                                                         self,
                                                                         requestId,
                                                                         followerSupplier.get().size() + 1,
                                                                         List.of(),
                                                                         evaluator.slidingWindow());
            }
        }
        return new workerMetricsAggregator(self,
                                           delegateRouter,
                                           passiveNode,
                                           communityIdSupplier,
                                           followerSupplier,
                                           aggregationIntervalMs,
                                           new ConcurrentHashMap<>(),
                                           CommunityScalingEvaluator.communityScalingEvaluator(),
                                           CancellableTask.cancellableTask());
    }
}
