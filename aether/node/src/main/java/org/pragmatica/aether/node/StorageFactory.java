package org.pragmatica.aether.node;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageReadinessGate;
import org.pragmatica.storage.StorageTier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Factory for creating hierarchical storage infrastructure from configuration.
/// Each named config entry produces a StorageSetup containing the storage instance,
/// snapshot manager, and readiness gate.
public final class StorageFactory {
    private static final Logger log = LoggerFactory.getLogger(StorageFactory.class);

    private static final long DEFAULT_MEMORY_BYTES = 256L * 1024 * 1024;

    private StorageFactory() {}

    public record StorageSetup(String name,
                               StorageInstance instance,
                               SnapshotManager snapshotManager,
                               StorageReadinessGate readinessGate) {
        public static StorageSetup storageSetup(String name,
                                                StorageInstance instance,
                                                SnapshotManager snapshotManager,
                                                StorageReadinessGate readinessGate) {
            return StorageSetup.storageSetup(name, instance, snapshotManager, readinessGate);
        }
    }

    static Map<String, StorageSetup> createAll(Map<String, StorageConfig> configs,
                                               String nodeId,
                                               Option<DHTClient> dhtClient) {
        var result = new LinkedHashMap<String, StorageSetup>();
        configs.forEach((name, config) -> createOne(name, config, nodeId, dhtClient).onSuccess(setup -> result.put(name,
                                                                                                                   setup))
                                                   .onFailure(cause -> log.error("Failed to create storage '{}': {}",
                                                                                 name,
                                                                                 cause.message())));
        return Map.copyOf(result);
    }

    static StorageInstance defaultArtifactStorage(Option<DHTClient> dhtClient) {
        var memoryTier = MemoryTier.memoryTier(DEFAULT_MEMORY_BYTES);
        return dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "artifact-blocks")).map(dht -> StorageInstance.storageInstance("artifacts",
                                                                                                                                            List.of(memoryTier,
                                                                                                                                                    dht)))
                            .or(StorageInstance.storageInstance("artifacts",
                                                                List.of(memoryTier)));
    }

    private static Result<StorageSetup> createOne(String name,
                                                  StorageConfig config,
                                                  String nodeId,
                                                  Option<DHTClient> dhtClient) {
        return buildTiers(name, config, dhtClient).map(tiers -> assembleSetup(name, tiers, config, nodeId));
    }

    private static Result<List<StorageTier>> buildTiers(String name,
                                                        StorageConfig config,
                                                        Option<DHTClient> dhtClient) {
        var memoryTier = MemoryTier.memoryTier(config.memoryMaxBytes());
        var dhtTier = dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, name + "-blocks"));
        return LocalDiskTier.localDiskTier(Path.of(config.diskPath()),
                                           config.diskMaxBytes())
        .fold(cause -> handleDiskTierUnavailable(name, cause, memoryTier, dhtTier),
              disk -> buildTierList(memoryTier, disk, dhtTier));
    }

    private static Result<List<StorageTier>> handleDiskTierUnavailable(String name,
                                                                       Cause cause,
                                                                       MemoryTier memoryTier,
                                                                       Option<DhtStorageTier> dhtTier) {
        log.warn("Disk tier for '{}' unavailable: {}, using memory + DHT fallback", name, cause.message());
        return Result.success(dhtTier.map(dht -> List.<StorageTier>of(memoryTier, dht)).or(List.of(memoryTier)));
    }

    private static Result<List<StorageTier>> buildTierList(MemoryTier memoryTier,
                                                           StorageTier diskTier,
                                                           Option<DhtStorageTier> dhtTier) {
        return Result.success(dhtTier.map(dht -> List.<StorageTier>of(memoryTier, diskTier, dht))
                                         .or(List.of(memoryTier, diskTier)));
    }

    private static StorageSetup assembleSetup(String name,
                                              List<StorageTier> tiers,
                                              StorageConfig config,
                                              String nodeId) {
        var metadataStore = MetadataStore.inMemoryMetadataStore(name);
        var instance = StorageInstance.storageInstance(name, tiers, metadataStore);
        var snapshotConfig = buildSnapshotConfig(config, nodeId);
        var snapshotManager = SnapshotManager.snapshotManager(metadataStore, snapshotConfig);
        var readinessGate = StorageReadinessGate.storageReadinessGate();
        restoreAndSignalReady(name, snapshotManager, metadataStore, readinessGate);
        log.info("Storage '{}' created: {} tier(s), snapshot path={}", name, tiers.size(), config.snapshotPath());
        return StorageSetup.storageSetup(name, instance, snapshotManager, readinessGate);
    }

    private static SnapshotConfig buildSnapshotConfig(StorageConfig config, String nodeId) {
        var intervalMillis = parseIntervalMillis(config.snapshotMaxInterval());
        return SnapshotConfig.snapshotConfig(Path.of(config.snapshotPath()),
                                             config.snapshotMutationThreshold(),
                                             intervalMillis,
                                             config.snapshotRetentionCount(),
                                             nodeId);
    }

    private static void restoreAndSignalReady(String name,
                                              SnapshotManager snapshotManager,
                                              MetadataStore metadataStore,
                                              StorageReadinessGate readinessGate) {
        snapshotManager.restoreFromLatest().onPresent(snapshot -> applySnapshot(name, snapshot, metadataStore));
        readinessGate.snapshotLoaded();
    }

    private static void applySnapshot(String name, MetadataSnapshot snapshot, MetadataStore metadataStore) {
        metadataStore.restoreLifecycles(snapshot.lifecycles());
        metadataStore.restoreRefs(snapshot.refs());
        log.info("Restored snapshot for '{}': epoch={}, lifecycles={}, refs={}",
                 name,
                 snapshot.epoch(),
                 snapshot.lifecycles().size(),
                 snapshot.refs().size());
    }

    private static long parseIntervalMillis(String interval) {
        return TimeSpan.timeSpan(interval).map(TimeSpan::toMillis)
                                .or(60_000L);
    }
}
