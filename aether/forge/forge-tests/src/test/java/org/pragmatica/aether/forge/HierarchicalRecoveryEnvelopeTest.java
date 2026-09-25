// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.forge;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.worker.metadata.WorkerMetadataMessage;
import org.pragmatica.aether.worker.metadata.WorkerMetadataLimits;
import org.pragmatica.aether.worker.health.CommunityHealthMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Local process envelope, not WAN or 10K-node throughput evidence. Region loss is an inbound
/// application-message partition; connections stay open and the three-core electorate stays intact.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalRecoveryEnvelopeTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan WARMUP = TimeSpan.timeSpan(3).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 31400, 31500, 31600, "envelope");
    private final List<NodeId> workers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean metadataBlackout = new AtomicBoolean();
    private final AtomicBoolean regionBlackout = new AtomicBoolean();
    private final AtomicBoolean reportBlackout = new AtomicBoolean();
    private final AtomicInteger droppedMetadata = new AtomicInteger();
    private final AtomicInteger droppedRegional = new AtomicInteger();
    private final AtomicInteger droppedReports = new AtomicInteger();
    private final AtomicInteger oversized = new AtomicInteger();

    @AfterEach void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop recovery envelope", cluster, cluster.stop());
    }

    @Test void measuredGrowthAndCorrelatedWorkerRecoveryPreserveControlPlane() {
        LifecycleAwait.settled("start envelope", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        for (int scale = 1; scale <= 3; scale++) {
            var started = System.nanoTime();
            workers.add(LifecycleAwait.nodeSettled("add envelope worker", cluster, cluster.addWorkerNode()));
            installFilters();
            await().atMost(BUDGET.duration()).until(() -> readiness(NodeReportedState.READY));
            var convergence = System.nanoTime() - started;
            await().pollDelay(WARMUP.duration()).atMost(WARMUP.duration().plusSeconds(2)).until(() -> true);
            var cpu = cpuTime();
            var before = transport();
            var latency = controlRequests(20);
            assertBounds();
            emit("steady", scale, convergence, 0, cpuTime() - cpu, before, transport(), latency);
        }
        var cpu = cpuTime();
        var before = transport();
        var started = System.nanoTime();
        reportBlackout.set(true);
        var during = controlRequests(10);
        await().atMost(BUDGET.duration()).until(() -> droppedReports.get() > 0);
        regionBlackout.set(true);
        await().atMost(TimeSpan.timeSpan(10).seconds().duration()).until(() -> droppedRegional.get() > 0);
        regionBlackout.set(false);
        reportBlackout.set(false);
        metadataBlackout.set(true);
        await().atMost(BUDGET.duration()).until(() -> droppedMetadata.get() >= workers.size());
        await().atMost(BUDGET.duration()).until(() -> readiness(NodeReportedState.SYNCING));
        during.addAll(controlRequests(10));
        assertBounds();
        metadataBlackout.set(false);
        var released = System.nanoTime();
        await().atMost(BUDGET.duration()).until(() -> readiness(NodeReportedState.READY));
        during.addAll(controlRequests(10));
        assertBounds();
        assertThat(oversized.get()).isZero();
        assertThat(leader().coreNodeIds()).hasSize(3).doesNotContainAnyElementsOf(workers);
        emit("correlated-worker-recovery", workers.size(), released - started, System.nanoTime() - released,
             cpuTime() - cpu, before, transport(), during);
    }

    private void installFilters() {
        var region = Set.copyOf(workers.subList(0, Math.min(2, workers.size())));
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((sender, message) -> {
            if (message instanceof WorkerMetadataMessage.Chunk chunk && chunk.bytes().length > WorkerMetadataLimits.DEFAULT.chunkBytes()) {
                oversized.incrementAndGet();
            }
            if (message instanceof WorkerMetadataMessage.Manifest manifest && (manifest.scopes().size() > WorkerMetadataLimits.DEFAULT.scopesPerWorker()
                || manifest.scopes().stream().anyMatch(scope -> scope.length() > WorkerMetadataLimits.DEFAULT.scopeBytes()))) {
                oversized.incrementAndGet();
            }
            if (regionBlackout.get() && (region.contains(sender) || region.contains(node.self()))) {
                droppedRegional.incrementAndGet();
                return false;
            }
            if (reportBlackout.get() && message instanceof CommunityHealthMessage.Report) {
                droppedReports.incrementAndGet();
                return false;
            }
            if (metadataBlackout.get() && workers.contains(node.self())
                && (message instanceof WorkerMetadataMessage.Manifest || message instanceof WorkerMetadataMessage.Chunk)) {
                droppedMetadata.incrementAndGet();
                return false;
            }
            return true;
        }));
    }

    private void assertBounds() {
        cluster.allNodes().forEach(node -> {
            var stats = node.metadataResourceMetrics();
            assertThat(stats).containsKeys("serverCachedBytes", "clientBufferBytes", "clientVerifiedBytes");
            assertThat(stats.get("serverCachedBytes")).isLessThanOrEqualTo(stats.get("serverCacheLimit"));
            assertThat(stats.get("serverManifests")).isLessThanOrEqualTo(stats.get("serverManifestLimit"));
            assertThat(stats.get("clientVerifiedBytes")).isLessThanOrEqualTo(stats.get("clientCacheLimit"));
            assertThat(stats.get("clientBufferBytes")).isLessThanOrEqualTo(stats.get("clientScopeLimit"));
        });
    }

    private boolean readiness(NodeReportedState state) {
        var core = leader();
        workers.forEach(worker -> core.route(new org.pragmatica.consensus.net.NetworkServiceMessage.Send(worker,
            new org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing(core.self(), Map.of(), 0, 0, 0,
                Set.of(), Set.of(), Map.of(), Set.of(), false, false))));
        return workers.stream().allMatch(worker -> core.metricsCollector().reportedStates().get(worker) == state);
    }

    private List<Long> controlRequests(int count) {
        var elapsed = new ArrayList<Long>();
        for (int index = 0; index < count; index++) {
            var start = System.nanoTime();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + leader().managementPort() + "/api/v1/metrics/transport"))
                .timeout(TimeSpan.timeSpan(5).seconds().duration()).GET().build();
            var response = jdkHttpOperations().sendString(request).await(TimeSpan.timeSpan(10).seconds()).unwrap();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isNotBlank();
            elapsed.add(System.nanoTime() - start);
        }
        return elapsed;
    }

    private Map<String, Long> transport() {
        var totals = new java.util.TreeMap<String, Long>();
        cluster.allNodes().forEach(node -> node.transportMetrics().forEach((key, value) -> totals.merge(key, value.longValue(), Long::sum)));
        assertThat(totals).isNotEmpty();
        return totals;
    }

    private long cpuTime() { return ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos(); }
    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private long percentile(List<Long> values, double quantile) {
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * quantile) - 1));
    }
    private String numbers(Map<String, Long> values) {
        return values.entrySet().stream().map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    private void emit(String phase, int scale, long convergence, long recovery, long cpu,
                      Map<String, Long> before, Map<String, Long> after, List<Long> latency) {
        System.out.println("HIERARCHY_ENVELOPE {\"phase\":\"" + phase + "\",\"simulation\":\"single-JVM inbound worker-region partition; metadata revalidation, not socket reconnect\","
            + "\"cores\":3,\"workers\":" + scale + ",\"processors\":" + Runtime.getRuntime().availableProcessors()
            + ",\"os\":\"" + System.getProperty("os.name") + "\",\"arch\":\"" + System.getProperty("os.arch")
            + "\",\"java\":\"" + System.getProperty("java.version") + "\",\"warmupMillis\":" + WARMUP.millis()
            + ",\"heapUsedBytes\":" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
            + ",\"heapMaxBytes\":" + Runtime.getRuntime().maxMemory() + ",\"processCpuNanos\":" + cpu
            + ",\"convergenceNanos\":" + convergence + ",\"recoveryNanos\":" + recovery
            + ",\"httpSamples\":" + latency.size() + ",\"httpP50Nanos\":" + percentile(latency, .50)
            + ",\"httpP95Nanos\":" + percentile(latency, .95) + ",\"httpP99Nanos\":" + percentile(latency, .99)
            + ",\"droppedRegional\":" + droppedRegional.get() + ",\"droppedReports\":" + droppedReports.get()
            + ",\"droppedMetadata\":" + droppedMetadata.get() + ",\"transportBefore\":" + numbers(before)
            + ",\"transportAfter\":" + numbers(after) + "}");
    }
}
