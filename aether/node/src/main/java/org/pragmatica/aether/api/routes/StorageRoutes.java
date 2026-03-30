package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.storage.ReadinessState;
import org.pragmatica.storage.StorageInstance.TierInfo;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for hierarchical storage management: list instances, instance detail, force snapshot.
public final class StorageRoutes implements RouteSource {
    private static final Cause STORAGE_NOT_FOUND = Causes.cause("Storage instance not found");

    private final Supplier<AetherNode> nodeSupplier;

    private StorageRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static StorageRoutes storageRoutes(Supplier<AetherNode> nodeSupplier) {
        return new StorageRoutes(nodeSupplier);
    }

    // --- Response records ---
    record TierDetail(TierLevel level, long usedBytes, long maxBytes, double utilizationPct) {
        static TierDetail tierDetail(TierInfo info) {
            return new TierDetail(info.level(),
                                  info.usedBytes(),
                                  info.maxBytes(),
                                  computeUtilization(info.usedBytes(), info.maxBytes()));
        }
    }

    record ReadinessDetail(ReadinessState state, boolean isReadReady, boolean isWriteReady) {}

    record SnapshotDetail(long lastEpoch, long lastTimestampMs) {}

    record StorageInstanceSummary(String name, List<TierDetail> tiers, ReadinessDetail readiness) {}

    record StorageListResponse(List<StorageInstanceSummary> instances) {}

    record StorageDetailResponse(String name,
                                 List<TierDetail> tiers,
                                 SnapshotDetail snapshot,
                                 ReadinessDetail readiness) {}

    record SnapshotResponse(String name, long epoch, long timestampMs) {}

    // --- Route definitions ---
    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<StorageListResponse> get("/api/storage")
                              .toJson(this::listInstances),
                         Route.<StorageDetailResponse> get("/api/storage")
                              .withPath(PathParameter.aString())
                              .toResult(this::instanceDetail)
                              .asJson(),
                         Route.<SnapshotResponse> post("/api/storage")
                              .withPath(PathParameter.aString(),
                                        PathParameter.spacer("snapshot"))
                              .toResult(this::forceSnapshot)
                              .asJson());
    }

    // --- Handlers ---
    private StorageListResponse listInstances() {
        var summaries = nodeSupplier.get()
                                    .storageSetups()
                                    .values()
                                    .stream()
                                    .map(StorageRoutes::toSummary)
                                    .toList();
        return new StorageListResponse(summaries);
    }

    private Result<StorageDetailResponse> instanceDetail(String name) {
        return findSetup(name)
            .map(StorageRoutes::toDetailResponse);
    }

    private Result<SnapshotResponse> forceSnapshot(String name, String spacer) {
        return findSetup(name)
            .map(StorageRoutes::triggerSnapshot);
    }

    // --- Helpers ---
    private Result<StorageSetup> findSetup(String name) {
        return Option.option(nodeSupplier.get()
                                         .storageSetups()
                                         .get(name))
                     .toResult(STORAGE_NOT_FOUND);
    }

    private static StorageInstanceSummary toSummary(StorageSetup setup) {
        return new StorageInstanceSummary(setup.name(),
                                          toTierDetails(setup),
                                          toReadinessDetail(setup));
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
        return setup.instance()
                    .tierInfo()
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
