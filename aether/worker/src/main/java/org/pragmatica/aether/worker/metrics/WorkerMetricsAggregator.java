package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Aggregates worker metrics on the governor and feeds the CommunityScalingEvaluator.
/// Created on governor election, destroyed on demotion.
///
/// Cycle:
///   1. Send WorkerMetricsPing to all group followers via WorkerNetwork
///   2. Collect own CPU/heap via ManagementFactory
///   3. Aggregate stored follower pongs + own metrics
///   4. Feed snapshot to CommunityScalingEvaluator
///   5. If evaluator returns scaling request, send via PassiveNode Broadcast
@SuppressWarnings({"JBCT-RET-01", "JBCT-ZONE-02", "JBCT-ZONE-03"})
public sealed interface WorkerMetricsAggregator permits ActiveWorkerMetricsAggregator {
    /// Start the aggregation cycle.
    void start();

    /// Stop the aggregation cycle.
    void stop();

    /// Called when a follower sends a metrics pong.
    void onMetricsPong(WorkerMetricsPong pong);

    /// Called when core requests a community metrics snapshot.
    void onSnapshotRequest(CommunityMetricsSnapshotRequest request);

    static WorkerMetricsAggregator workerMetricsAggregator(NodeId self,
                                                           WorkerNetwork workerNetwork,
                                                           PassiveNode<?, ?> passiveNode,
                                                           Supplier<String> communityIdSupplier,
                                                           Supplier<List<NodeId>> followerSupplier,
                                                           long aggregationIntervalMs) {
        return new ActiveWorkerMetricsAggregator(self,
                                                 workerNetwork,
                                                 passiveNode,
                                                 communityIdSupplier,
                                                 followerSupplier,
                                                 aggregationIntervalMs,
                                                 new ConcurrentHashMap<>(),
                                                 CommunityScalingEvaluator.communityScalingEvaluator(),
                                                 new AtomicReference<>());
    }
}

@SuppressWarnings({"JBCT-STY-05", "JBCT-RET-01", "JBCT-ZONE-02", "JBCT-ZONE-03", "JBCT-EX-01"})
final class ActiveWorkerMetricsAggregator implements WorkerMetricsAggregator {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricsAggregator.class);
    private static final int STALE_MULTIPLIER = 2;

    private final NodeId self;
    private final WorkerNetwork workerNetwork;
    private final PassiveNode<?, ?> passiveNode;
    private final Supplier<String> communityIdSupplier;
    private final Supplier<List<NodeId>> followerSupplier;
    private final long aggregationIntervalMs;
    private final ConcurrentHashMap<NodeId, WorkerMetricsPong> pongs;
    private final CommunityScalingEvaluator scalingEvaluator;
    private final AtomicReference<ScheduledFuture<?>> task;

    ActiveWorkerMetricsAggregator(NodeId self,
                                  WorkerNetwork workerNetwork,
                                  PassiveNode<?, ?> passiveNode,
                                  Supplier<String> communityIdSupplier,
                                  Supplier<List<NodeId>> followerSupplier,
                                  long aggregationIntervalMs,
                                  ConcurrentHashMap<NodeId, WorkerMetricsPong> pongs,
                                  CommunityScalingEvaluator scalingEvaluator,
                                  AtomicReference<ScheduledFuture<?>> task) {
        this.self = self;
        this.workerNetwork = workerNetwork;
        this.passiveNode = passiveNode;
        this.communityIdSupplier = communityIdSupplier;
        this.followerSupplier = followerSupplier;
        this.aggregationIntervalMs = aggregationIntervalMs;
        this.pongs = pongs;
        this.scalingEvaluator = scalingEvaluator;
        this.task = task;
    }

    @Override
    public void start() {
        stop();
        var scheduled = SharedScheduler.scheduleAtFixedRate(this::runCycle,
                                                            TimeSpan.timeSpan(aggregationIntervalMs)
                                                                    .millis());
        task.set(scheduled);
        LOG.info("Started metrics aggregator for governor {}", self.id());
    }

    @Override
    public void stop() {
        Option.option(task.getAndSet(null))
              .onPresent(existing -> existing.cancel(false));
        pongs.clear();
        scalingEvaluator.reset();
        LOG.debug("Stopped metrics aggregator for governor {}", self.id());
    }

    @Override
    public void onMetricsPong(WorkerMetricsPong pong) {
        pongs.put(pong.sender(), pong);
    }

    @Override
    public void onSnapshotRequest(CommunityMetricsSnapshotRequest request) {
        var communityId = communityIdSupplier.get();
        if (!communityId.equals(request.communityId())) {
            return;
        }
        var snapshot = buildSnapshot(communityId, request.requestId());
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.Broadcast(snapshot));
    }

    ConcurrentHashMap<NodeId, WorkerMetricsPong> pongStore() {
        return pongs;
    }

    CommunityScalingEvaluator evaluator() {
        return scalingEvaluator;
    }

    private void runCycle() {
        try{
            sendPingToFollowers();
            var ownMetrics = collectOwnMetrics();
            cleanupStalePongs();
            var sample = aggregateMetrics(ownMetrics);
            evaluateAndScale(sample);
        } catch (Exception e) {
            LOG.error("Metrics aggregation cycle error: {}", e.getMessage(), e);
        }
    }

    private void sendPingToFollowers() {
        var ping = WorkerMetricsPing.workerMetricsPing(self);
        followerSupplier.get()
                        .forEach(followerId -> workerNetwork.send(followerId, ping));
    }

    private WorkerMetricsPong collectOwnMetrics() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        var cpuLoad = Math.max(0.0,
                               osBean.getSystemLoadAverage() / Runtime.getRuntime()
                                                                     .availableProcessors());
        var heapUsed = memBean.getHeapMemoryUsage()
                              .getUsed();
        var heapMax = memBean.getHeapMemoryUsage()
                             .getMax();
        var heapUsage = heapMax > 0
                        ? (double) heapUsed / heapMax
                        : 0.0;
        return WorkerMetricsPong.workerMetricsPong(self, cpuLoad, heapUsage, 0L, 0.0, 0.0);
    }

    private void cleanupStalePongs() {
        var cutoff = System.currentTimeMillis() - (STALE_MULTIPLIER * aggregationIntervalMs);
        pongs.entrySet()
             .removeIf(entry -> entry.getValue()
                                     .timestampMs() < cutoff);
    }

    private WindowSample aggregateMetrics(WorkerMetricsPong ownMetrics) {
        var allPongs = new ArrayList<>(pongs.values());
        allPongs.add(ownMetrics);
        return WindowSample.windowSample(allPongs.stream()
                                                 .mapToDouble(WorkerMetricsPong::cpuUsage)
                                                 .average()
                                                 .orElse(0.0),
                                         allPongs.stream()
                                                 .mapToDouble(WorkerMetricsPong::heapUsage)
                                                 .average()
                                                 .orElse(0.0),
                                         allPongs.stream()
                                                 .mapToLong(WorkerMetricsPong::activeInvocations)
                                                 .sum(),
                                         allPongs.stream()
                                                 .mapToDouble(WorkerMetricsPong::p95LatencyMs)
                                                 .average()
                                                 .orElse(0.0),
                                         allPongs.stream()
                                                 .mapToDouble(WorkerMetricsPong::errorRate)
                                                 .average()
                                                 .orElse(0.0));
    }

    private void evaluateAndScale(WindowSample sample) {
        var communityId = communityIdSupplier.get();
        var memberCount = followerSupplier.get()
                                          .size() + 1;
        scalingEvaluator.evaluate(communityId, self, memberCount, sample)
                        .onPresent(this::sendScalingRequest);
    }

    private void sendScalingRequest(CommunityScalingRequest request) {
        LOG.info("Sending {} scaling request for community '{}'", request.direction(), request.communityId());
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.Broadcast(request));
    }

    private CommunityMetricsSnapshot buildSnapshot(String communityId, long requestId) {
        return CommunityMetricsSnapshot.communityMetricsSnapshot(communityId,
                                                                 self,
                                                                 requestId,
                                                                 followerSupplier.get()
                                                                                 .size() + 1,
                                                                 List.of(),
                                                                 scalingEvaluator.slidingWindow());
    }
}
