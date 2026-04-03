package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StorageStatusKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue.TierStatus;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.storage.ReadinessState;
import org.pragmatica.storage.StorageInstance.TierInfo;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// Routes for hierarchical storage management: per-node instance status and cluster-wide aggregation.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"}) public final class StorageRoutes implements RouteSource {
    private static final Cause STORAGE_NOT_FOUND = Causes.cause("Storage instance not found");

    private static final Cause CLUSTER_INSTANCE_NOT_FOUND = Causes.cause("Storage instance not found in cluster");

    private final Supplier<AetherNode> nodeSupplier;

    private StorageRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static StorageRoutes storageRoutes(Supplier<AetherNode> nodeSupplier) {
        return new StorageRoutes(nodeSupplier);
    }

    record TierDetail(TierLevel level, long usedBytes, long maxBytes, double utilizationPct) {
        static TierDetail tierDetail(TierInfo info) {
            return new TierDetail(info.level(),
                                  info.usedBytes(),
                                  info.maxBytes(),
                                  computeUtilization(info.usedBytes(), info.maxBytes()));
        }
    }

    record ReadinessDetail(ReadinessState state, boolean isReadReady, boolean isWriteReady){}

    record SnapshotDetail(long lastEpoch, long lastTimestampMs){}

    record StorageInstanceSummary(String name, List<TierDetail> tiers, ReadinessDetail readiness){}

    record StorageListResponse(List<StorageInstanceSummary> instances){}

    record StorageDetailResponse(String name,
                                 List<TierDetail> tiers,
                                 SnapshotDetail snapshot,
                                 ReadinessDetail readiness){}

    record SnapshotResponse(String name, long epoch, long timestampMs){}

    record NodeStorageSummary(String nodeId, List<TierDetail> tiers, ReadinessDetail readiness){}

    record ClusterStorageInstanceSummary(String name,
                                         int nodeCount,
                                         long totalUsedBytes,
                                         long totalMaxBytes,
                                         List<NodeStorageSummary> nodes){}

    record ClusterStorageListResponse(List<ClusterStorageInstanceSummary> instances){}

    record NodeStorageDetail(String nodeId,
                             List<TierDetail> tiers,
                             SnapshotDetail snapshot,
                             ReadinessDetail readiness){}

    record ClusterStorageDetailResponse(String name,
                                        int nodeCount,
                                        long totalUsedBytes,
                                        long totalMaxBytes,
                                        List<NodeStorageDetail> nodes){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<StorageListResponse>get("/api/storage").toJson(this::listInstances),
                         Route.<StorageDetailResponse>get("/api/storage")
                              .withPath(PathParameter.aString())
                              .toResult(this::instanceDetail)
                              .asJson(),
                         Route.<SnapshotResponse>post("/api/storage")
                              .withPath(PathParameter.aString(),
                                        PathParameter.spacer("snapshot"))
                              .toResult(this::forceSnapshot)
                              .asJson(),
                         Route.<ClusterStorageListResponse>get("/api/cluster/storage")
                              .to(_ -> clusterListInstances())
                              .asJson(),
                         Route.<ClusterStorageDetailResponse>get("/api/cluster/storage")
                              .withPath(PathParameter.aString())
                              .to(this::clusterInstanceDetail)
                              .asJson());
    }

    private StorageListResponse listInstances() {
        var summaries = nodeSupplier.get().storageSetups()
                                        .values()
                                        .stream()
                                        .map(StorageRoutes::toSummary)
                                        .toList();
        return new StorageListResponse(summaries);
    }

    private Result<StorageDetailResponse> instanceDetail(String name) {
        return findSetup(name).map(StorageRoutes::toDetailResponse);
    }

    private Result<SnapshotResponse> forceSnapshot(String name, String spacer) {
        return findSetup(name).map(StorageRoutes::triggerSnapshot);
    }

    @SuppressWarnings("unchecked") private Promise<ClusterStorageListResponse> clusterListInstances() {
        var node = nodeSupplier.get();
        var commands = buildPublishCommands(node);
        return node.<Object>apply(commands).map(_ -> collectClusterListResponse(node));
    }

    @SuppressWarnings("unchecked") private Promise<ClusterStorageDetailResponse> clusterInstanceDetail(String name) {
        var node = nodeSupplier.get();
        var commands = buildPublishCommands(node);
        return node.<Object>apply(commands).flatMap(_ -> buildClusterDetailResponse(node, name));
    }

    @SuppressWarnings("unchecked") private static List<KVCommand<AetherKey>> buildPublishCommands(AetherNode node) {
        var nodeId = node.self();
        return node.storageSetups().entrySet()
                                 .stream()
                                 .map(e -> (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(StorageStatusKey.storageStatusKey(nodeId,
                                                                                                                                      e.getKey()),
                                                                                                    buildStatusValue(e.getValue())))
                                 .toList();
    }

    private static StorageStatusValue buildStatusValue(StorageSetup setup) {
        var tiers = setup.instance().tierInfo()
                                  .stream()
                                  .map(StorageRoutes::toTierStatus)
                                  .toList();
        var gate = setup.readinessGate();
        return StorageStatusValue.storageStatusValue(setup.name(),
                                                     tiers,
                                                     gate.state().name(),
                                                     gate.isReadReady(),
                                                     gate.isWriteReady(),
                                                     setup.snapshotManager().lastSnapshotEpoch(),
                                                     setup.snapshotManager().lastSnapshotTimestamp());
    }

    private static TierStatus toTierStatus(TierInfo info) {
        return TierStatus.tierStatus(info.level().name(),
                                     info.usedBytes(),
                                     info.maxBytes());
    }

    private static ClusterStorageListResponse collectClusterListResponse(AetherNode node) {
        var grouped = collectStatusesByInstance(node);
        var instances = grouped.entrySet().stream()
                                        .map(StorageRoutes::toClusterInstanceSummary)
                                        .toList();
        return new ClusterStorageListResponse(instances);
    }

    private static ClusterStorageInstanceSummary toClusterInstanceSummary(Map.Entry<String, List<Map.Entry<StorageStatusKey, StorageStatusValue>>> entry) {
        var name = entry.getKey();
        var entries = entry.getValue();
        var nodes = entries.stream().map(StorageRoutes::toNodeStorageSummary)
                                  .toList();
        return new ClusterStorageInstanceSummary(name,
                                                 nodes.size(),
                                                 totalUsedBytes(entries),
                                                 totalMaxBytes(entries),
                                                 nodes);
    }

    private static long totalUsedBytes(List<Map.Entry<StorageStatusKey, StorageStatusValue>> entries) {
        return entries.stream().flatMap(e -> e.getValue().tiers()
                                                       .stream())
                             .mapToLong(TierStatus::usedBytes)
                             .sum();
    }

    private static long totalMaxBytes(List<Map.Entry<StorageStatusKey, StorageStatusValue>> entries) {
        return entries.stream().flatMap(e -> e.getValue().tiers()
                                                       .stream())
                             .mapToLong(TierStatus::maxBytes)
                             .sum();
    }

    private static NodeStorageSummary toNodeStorageSummary(Map.Entry<StorageStatusKey, StorageStatusValue> entry) {
        var value = entry.getValue();
        var tiers = value.tiers().stream()
                               .map(StorageRoutes::statusTierToDetail)
                               .toList();
        var readiness = parseReadinessDetail(value);
        return new NodeStorageSummary(entry.getKey().nodeId()
                                                  .id(),
                                      tiers,
                                      readiness);
    }

    private static Promise<ClusterStorageDetailResponse> buildClusterDetailResponse(AetherNode node, String name) {
        var grouped = collectStatusesByInstance(node);
        return Option.option(grouped.get(name)).filter(entries -> !entries.isEmpty())
                            .map(entries -> assembleClusterDetail(name, entries))
                            .toResult(CLUSTER_INSTANCE_NOT_FOUND)
                            .async();
    }

    private static ClusterStorageDetailResponse assembleClusterDetail(String name,
                                                                      List<Map.Entry<StorageStatusKey, StorageStatusValue>> entries) {
        var nodes = entries.stream().map(StorageRoutes::toNodeStorageDetail)
                                  .toList();
        return new ClusterStorageDetailResponse(name,
                                                nodes.size(),
                                                totalUsedBytes(entries),
                                                totalMaxBytes(entries),
                                                nodes);
    }

    private static NodeStorageDetail toNodeStorageDetail(Map.Entry<StorageStatusKey, StorageStatusValue> entry) {
        var value = entry.getValue();
        var tiers = value.tiers().stream()
                               .map(StorageRoutes::statusTierToDetail)
                               .toList();
        var snapshot = new SnapshotDetail(value.lastSnapshotEpoch(), value.lastSnapshotTimestamp());
        var readiness = parseReadinessDetail(value);
        return new NodeStorageDetail(entry.getKey().nodeId()
                                                 .id(),
                                     tiers,
                                     snapshot,
                                     readiness);
    }

    private static Map<String, List<Map.Entry<StorageStatusKey, StorageStatusValue>>> collectStatusesByInstance(AetherNode node) {
        var grouped = new LinkedHashMap<String, List<Map.Entry<StorageStatusKey, StorageStatusValue>>>();
        node.kvStore()
                    .forEach(StorageStatusKey.class,
                             StorageStatusValue.class,
                             (key, value) -> grouped.computeIfAbsent(key.instanceName(),
                                                                     _ -> new ArrayList<>())
        .add(Map.entry(key, value)));
        return grouped;
    }

    private static TierDetail statusTierToDetail(TierStatus tier) {
        return new TierDetail(TierLevel.valueOf(tier.level()),
                              tier.usedBytes(),
                              tier.maxBytes(),
                              computeUtilization(tier.usedBytes(), tier.maxBytes()));
    }

    private static ReadinessDetail parseReadinessDetail(StorageStatusValue value) {
        return new ReadinessDetail(ReadinessState.valueOf(value.readinessState()),
                                   value.isReadReady(),
                                   value.isWriteReady());
    }

    private Result<StorageSetup> findSetup(String name) {
        return Option.option(nodeSupplier.get().storageSetups()
                                             .get(name)).toResult(STORAGE_NOT_FOUND);
    }

    private static StorageInstanceSummary toSummary(StorageSetup setup) {
        return new StorageInstanceSummary(setup.name(), toTierDetails(setup), toReadinessDetail(setup));
    }

    private static StorageDetailResponse toDetailResponse(StorageSetup setup) {
        return new StorageDetailResponse(setup.name(),
                                         toTierDetails(setup),
                                         toSnapshotDetail(setup),
                                         toReadinessDetail(setup));
    }

    private static SnapshotResponse triggerSnapshot(StorageSetup setup) {
        setup.snapshotManager().forceSnapshot();
        return new SnapshotResponse(setup.name(),
                                    setup.snapshotManager().lastSnapshotEpoch(),
                                    setup.snapshotManager().lastSnapshotTimestamp());
    }

    private static List<TierDetail> toTierDetails(StorageSetup setup) {
        return setup.instance().tierInfo()
                             .stream()
                             .map(TierDetail::tierDetail)
                             .toList();
    }

    private static ReadinessDetail toReadinessDetail(StorageSetup setup) {
        var gate = setup.readinessGate();
        return new ReadinessDetail(gate.state(), gate.isReadReady(), gate.isWriteReady());
    }

    private static SnapshotDetail toSnapshotDetail(StorageSetup setup) {
        return new SnapshotDetail(setup.snapshotManager().lastSnapshotEpoch(),
                                  setup.snapshotManager().lastSnapshotTimestamp());
    }

    private static double computeUtilization(long usedBytes, long maxBytes) {
        return maxBytes > 0
              ? Math.round(usedBytes * 1000.0 / maxBytes) / 10.0
              : 0.0;
    }
}
