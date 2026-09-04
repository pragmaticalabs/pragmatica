// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.DemotionConfig;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.GarbageCollectorConfig;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageReadinessGate;
import org.pragmatica.storage.StorageTier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class StorageFactory {
    private static final Logger log = LoggerFactory.getLogger(StorageFactory.class);
    private static final long DEFAULT_MEMORY_BYTES = 256L * 1024 * 1024;
    private static final String STREAMS_NAME = "streams";
    /// Hot-ring mirror in the memory tier — small; the live ring already holds recent events,
    /// the memory tier is only the first read-waterfall hop for just-sealed segment blocks.
    private static final long STREAM_MEMORY_BYTES = 16L * 1024 * 1024;
    /// Durable segment-block cap on the local disk tier. Larger than the memory tier: this is the
    /// substrate that lets sealed segments survive a same-node restart. Not pre-allocated — it is a
    /// reservation ceiling enforced per-write by `LocalDiskTier`.
    private static final long STREAM_DISK_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int STREAM_SNAPSHOT_MUTATION_THRESHOLD = 100;
    private static final long STREAM_SNAPSHOT_INTERVAL_MILLIS = 30_000L;
    private static final int STREAM_SNAPSHOT_RETENTION_COUNT = 5;
    /// #253 SHOULD-FIX #1 (2026-09-04 ruling): bounds the DHT marker put/get that
    /// [#maybeEncryptDht]/[#refuseIfDhtEncryptedWithoutKeyring] perform at boot -- otherwise fully
    /// synchronous, boot-time code bridging into `DHTClient`'s `Promise`-based API. Same rationale
    /// and value as `StorageEncryption.RESOLUTION_TIMEOUT`: a hung round-trip must fail boot, not
    /// hang it. Named `org.pragmatica.lang.io.TimeSpan` in full because this file already imports
    /// the unrelated `org.pragmatica.lang.parse.TimeSpan` under the simple name.
    private static final org.pragmatica.lang.io.TimeSpan DHT_MARKER_TIMEOUT = timeSpan(30).seconds();

    private StorageFactory() {}

    public record StorageSetup(String name,
                               StorageInstance instance,
                               SnapshotManager snapshotManager,
                               StorageReadinessGate readinessGate,
                               MetadataStore metadataStore,
                               DemotionManager demotionManager,
                               StorageGarbageCollector garbageCollector) {
        public static StorageSetup storageSetup(String name,
                                                StorageInstance instance,
                                                SnapshotManager snapshotManager,
                                                StorageReadinessGate readinessGate,
                                                MetadataStore metadataStore,
                                                DemotionManager demotionManager,
                                                StorageGarbageCollector garbageCollector) {
            return new StorageSetup(name,
                                    instance,
                                    snapshotManager,
                                    readinessGate,
                                    metadataStore,
                                    demotionManager,
                                    garbageCollector);
        }
    }

    /// #250: fan out demotion across every storage setup so leader-pinned activation
    /// (`DelegatedStorageAdapter`) and the periodic maintenance tick (`StorageMaintenanceDriver`)
    /// each see ONE `DemotionManager` regardless of how many storage instances the node runs.
    /// Each child is independently self-limiting on its own tier watermarks, so summing `demote()`
    /// results and fanning out `activate()`/`deactivate()` is safe and requires no coordination.
    public static DemotionManager compositeDemotionManager(Map<String, StorageSetup> setups) {
        var managers = setups.values().stream().map(StorageSetup::demotionManager).toList();

        return new DemotionManager() {
            @Override
            public int demote() {
                return managers.stream()
                               .mapToInt(DemotionManager::demote)
                               .sum();
            }

            @Override
            public DemotionStats stats() {
                return managers.stream()
                               .map(DemotionManager::stats)
                               .reduce(StorageFactory::mergeDemotionStats)
                               .orElseGet(() -> new DemotionStats(0, 0, 0));
            }

            @Override
            public Result<Unit> activate() {
                return Result.allOf(managers.stream().map(DemotionManager::activate).toList()).map(_ -> unit());
            }

            @Override
            public Result<Unit> deactivate() {
                return Result.allOf(managers.stream().map(DemotionManager::deactivate).toList()).map(_ -> unit());
            }

            @Override
            public boolean isActive() {
                return managers.stream()
                               .allMatch(DemotionManager::isActive);
            }
        };
    }

    private static DemotionManager.DemotionStats mergeDemotionStats(DemotionManager.DemotionStats a,
                                                                    DemotionManager.DemotionStats b) {
        return new DemotionManager.DemotionStats(a.blocksDemoted() + b.blocksDemoted(),
                                                 a.bytesMoved() + b.bytesMoved(),
                                                 Math.max(a.lastRunMs(), b.lastRunMs()));
    }

    /// #250: same fan-out as [#compositeDemotionManager], for garbage collection.
    public static StorageGarbageCollector compositeGarbageCollector(Map<String, StorageSetup> setups) {
        var collectors = setups.values().stream().map(StorageSetup::garbageCollector).toList();

        return new StorageGarbageCollector() {
            @Override
            public int collectGarbage() {
                return collectors.stream()
                                 .mapToInt(StorageGarbageCollector::collectGarbage)
                                 .sum();
            }

            @Override
            public GCStats stats() {
                return collectors.stream()
                                 .map(StorageGarbageCollector::stats)
                                 .reduce(StorageFactory::mergeGcStats)
                                 .orElseGet(() -> new GCStats(0, 0));
            }

            @Override
            public Result<Unit> activate() {
                return Result.allOf(collectors.stream().map(StorageGarbageCollector::activate).toList()).map(_ -> unit());
            }

            @Override
            public Result<Unit> deactivate() {
                return Result.allOf(collectors.stream().map(StorageGarbageCollector::deactivate).toList()).map(_ -> unit());
            }

            @Override
            public boolean isActive() {
                return collectors.stream()
                                 .allMatch(StorageGarbageCollector::isActive);
            }
        };
    }

    private static StorageGarbageCollector.GCStats mergeGcStats(StorageGarbageCollector.GCStats a,
                                                                StorageGarbageCollector.GCStats b) {
        return new StorageGarbageCollector.GCStats(a.blocksCollected() + b.blocksCollected(),
                                                   Math.max(a.lastRunMs(), b.lastRunMs()));
    }

    /// #253 BLOCKING #1 (2026-09-04 ruling): a configured instance that fails to create is a boot
    /// failure -- the old log-and-drop here (paired with `AetherNode`'s "artifacts" substitution
    /// fallback) let a `wrapLocalDisk` refusal boot the node anyway on a hardcoded, unencrypted
    /// memory+DHT instance. Every entry -- explicit `[storage.X]` config AND the synthesized
    /// default `artifacts` instance below -- goes through the SAME `Result`-returning path and is
    /// combined with [Result#firstFailureOf], mirroring the single-failure-aborts pattern this
    /// class already uses for `streamStorageResult`-shaped callers: the first failure aborts
    /// `createAll` outright, naming the instance (via `createOne`'s wrapping) and the underlying
    /// cause, rather than silently dropping that one instance and continuing.
    static Result<Map<String, StorageSetup>> createAll(Map<String, StorageConfig> configs,
                                                       String nodeId,
                                                       Option<DHTClient> dhtClient,
                                                       Option<EncryptionKeyring> keyring) {
        var results = new ArrayList<Result<StorageSetup>>();

        configs.forEach((name, config) -> results.add(createOne(name, config, nodeId, dhtClient, keyring)));
        // Every node carries an `artifacts` storage instance — operators expect it without
        // having to opt-in via `[storage.artifacts]` in aether.toml. If explicit config wasn't
        // provided, synthesize one using `StorageConfig.storageConfig()` defaults; explicit
        // config still wins via the loop above. `createOne` reuses the same code path
        // (`handleDiskTierUnavailable` falls back to memory+DHT when the default disk path
        // isn't mountable, e.g. inside the aether-node container). #253 ruling (2026-09-04): an
        // operator who turns on `[storage.encryption]` must not have this auto-created instance
        // silently stay plaintext merely because it has no explicit `[storage.artifacts]`
        // section -- the synthesized config's `encrypted` flag now tracks keyring presence, same
        // outcome as an explicit `encrypted = true` section -- and if IT fails to create, that is
        // a boot failure exactly like an explicit instance's, not a silently-dropped default.
        if (!configs.containsKey(ARTIFACTS_NAME)) {
            results.add(createOne(ARTIFACTS_NAME,
                                  defaultArtifactsConfig(keyring.isPresent()),
                                  nodeId,
                                  dhtClient,
                                  keyring));
        }

        return Result.firstFailureOf(results).map(setups -> setups.stream()
                                                                  .collect(Collectors.toMap(StorageSetup::name,
                                                                                            Function.identity())));
    }

    /// #253: `StorageConfig.storageConfig()`'s defaults with `encrypted` overridden to track
    /// node-wide keyring presence, for the synthesized default `artifacts` instance in
    /// [#createAll] -- see the ruling note there.
    private static StorageConfig defaultArtifactsConfig(boolean encrypted) {
        var defaults = StorageConfig.storageConfig();

        return new StorageConfig(defaults.memoryMaxBytes(),
                                 defaults.diskMaxBytes(),
                                 defaults.diskPath(),
                                 defaults.snapshotPath(),
                                 defaults.snapshotMutationThreshold(),
                                 defaults.snapshotMaxInterval(),
                                 defaults.snapshotRetentionCount(),
                                 defaults.walPath(),
                                 encrypted);
    }

    private static final String ARTIFACTS_NAME = "artifacts";

    /// Default `StorageInstance` backing slice-facing `ContentStore` resources (#251). Registered as
    /// the SPI `StorageInstance` extension so `ContentStoreFactory.provision(config, context)` can
    /// resolve a tiered store — without this, ContentStore provisioning fails at runtime with
    /// "requires ProvisioningContext with StorageInstance extension". Memory cache tier over a DHT
    /// durable tier (memory-only when no DHT client is wired) -- the same shape `createAll`'s
    /// synthesized default `artifacts` instance uses, but `content` has no config-driven,
    /// keyring-aware path (#783: architecturally unencryptable under #253, tracked separately).
    static StorageInstance defaultContentStorage(Option<DHTClient> dhtClient) {
        var memoryTier = MemoryTier.memoryTier(DEFAULT_MEMORY_BYTES);

        return dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "content-blocks"))
                        .map(dht -> StorageInstance.storageInstance("content",
                                                                    List.of(memoryTier, dht)))
                        .or(StorageInstance.storageInstance("content",
                                                            List.of(memoryTier)));
    }

    /// Build the disk-backed, snapshot-capable `streams` StorageSetup that durably backs the stream
    /// segment store. Tiers are layered memory -> LocalDisk -> DHT (hot read hop, durable local
    /// segments, replication), the in-memory MetadataStore is wrapped by a SnapshotManager that
    /// restores refs at boot, and both the segment blocks (`<streamDataDir>/segments`) and the
    /// metadata snapshots (`<streamDataDir>/snapshots`) live under the caller-supplied per-node
    /// `streamDataDir` so blocks and refs survive a same-node restart. The disk tier degrades to
    /// memory+DHT when `streamDataDir` is not writable (mirrors `createOne`'s
    /// `handleDiskTierUnavailable`), so node boot never fails on an unmountable data dir.
    static StorageSetup defaultStreamStorage(Option<DHTClient> dhtClient, Path streamDataDir, String nodeId) {
        var tiers = buildStreamTiers(dhtClient, streamDataDir.resolve("segments"));

        return assembleStreamSetup(tiers, streamDataDir.resolve("snapshots"), nodeId);
    }

    /// #253 — encrypted counterpart to the three-arg overload above. Streams has no per-instance
    /// `StorageConfig#encrypted()` of its own to consult (`[storage.encryption] streams_encrypted`
    /// is a dedicated top-level flag) so the caller resolves that decision and hands this method
    /// `Option.empty()` when streams isn't encrypted -- in which case this delegates to the plain
    /// overload unchanged. When a keyring IS supplied, this can FAIL where the plain overload
    /// cannot: `EncryptingStorageTier#wrapLocalDisk` refuses rather than silently leaving data
    /// unencrypted when the segments dir already holds unmarked plaintext blocks from a prior
    /// unencrypted boot.
    ///
    /// #253 BLOCKING #3 extension (2026-09-04, beyond the two call sites the review cited): the
    /// no-keyring branch has the identical reverse-direction gap as `buildTierList` -- a prior
    /// encrypted boot's `.encryption-enabled` marker under `<streamDataDir>/segments` went
    /// unchecked, so disabling `streams_encrypted` (or dropping `[storage.encryption]`) would
    /// silently hand back framed ciphertext as plaintext through `buildStreamTiers`' bare disk
    /// tier. Same guard, same architecture as the per-instance path.
    static Result<StorageSetup> defaultStreamStorage(Option<DHTClient> dhtClient,
                                                     Path streamDataDir,
                                                     String nodeId,
                                                     Option<EncryptionKeyring> keyring) {
        return keyring.fold(() -> EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(streamDataDir.resolve("segments"),
                                                                                        STREAMS_NAME)
                                                       .map(_ -> defaultStreamStorage(dhtClient, streamDataDir, nodeId)),
                            ring -> buildEncryptedStreamTiers(dhtClient, streamDataDir.resolve("segments"), ring).map(tiers -> assembleStreamSetup(tiers,
                                                                                                                                                   streamDataDir.resolve("snapshots"),
                                                                                                                                                   nodeId)));
    }

    private static Result<List<StorageTier>> buildEncryptedStreamTiers(Option<DHTClient> dhtClient,
                                                                       Path segmentsDir,
                                                                       EncryptionKeyring keyring) {
        var memoryTier = MemoryTier.memoryTier(STREAM_MEMORY_BYTES);
        var dhtTier = dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "stream-segments"))
                               .map(dht -> EncryptingStorageTier.wrap(dht, keyring));

        return LocalDiskTier.localDiskTier(segmentsDir, STREAM_DISK_BYTES).fold(cause -> {
                                                                                    log.warn("Disk tier for 'streams' unavailable: {}, using memory + DHT fallback",
                                                                                             cause.message());

                                                                                    return Result.success(dhtTier.map(dht -> List.<StorageTier> of(memoryTier,
                                                                                                                                                   dht))
                                                                                                                 .or(List.of(memoryTier)));
                                                                                },
                                                                                disk -> EncryptingStorageTier.wrapLocalDisk(disk,
                                                                                                                            segmentsDir,
                                                                                                                            keyring).map(encDisk -> dhtTier.map(dht -> List.<StorageTier> of(memoryTier,
                                                                                                                                                                                             encDisk,
                                                                                                                                                                                             dht))
                                                                                                                                                           .or(List.of(memoryTier,
                                                                                                                                                                       encDisk))));
    }

    private static List<StorageTier> buildStreamTiers(Option<DHTClient> dhtClient, Path segmentsDir) {
        var memoryTier = MemoryTier.memoryTier(STREAM_MEMORY_BYTES);
        var dhtTier = dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "stream-segments"));

        return LocalDiskTier.localDiskTier(segmentsDir, STREAM_DISK_BYTES).fold(cause -> streamTiersWithoutDisk(cause,
                                                                                                                memoryTier,
                                                                                                                dhtTier),
                                                                                disk -> streamTiers(memoryTier,
                                                                                                    disk,
                                                                                                    dhtTier));
    }

    private static List<StorageTier> streamTiersWithoutDisk(Cause cause,
                                                            MemoryTier memoryTier,
                                                            Option<DhtStorageTier> dhtTier) {
        log.warn("Disk tier for 'streams' unavailable: {}, using memory + DHT fallback", cause.message());

        return dhtTier.map(dht -> List.<StorageTier> of(memoryTier, dht))
                      .or(List.of(memoryTier));
    }

    private static List<StorageTier> streamTiers(MemoryTier memoryTier,
                                                 StorageTier diskTier,
                                                 Option<DhtStorageTier> dhtTier) {
        return dhtTier.map(dht -> List.<StorageTier> of(memoryTier, diskTier, dht))
                      .or(List.of(memoryTier, diskTier));
    }

    private static StorageSetup assembleStreamSetup(List<StorageTier> tiers, Path snapshotDir, String nodeId) {
        var metadataStore = MetadataStore.inMemoryMetadataStore(STREAMS_NAME);
        var instance = StorageInstance.storageInstance(STREAMS_NAME, tiers, metadataStore);
        var snapshotConfig = SnapshotConfig.snapshotConfig(snapshotDir,
                                                           STREAM_SNAPSHOT_MUTATION_THRESHOLD,
                                                           STREAM_SNAPSHOT_INTERVAL_MILLIS,
                                                           STREAM_SNAPSHOT_RETENTION_COUNT,
                                                           nodeId);
        var snapshotManager = SnapshotManager.snapshotManager(metadataStore, snapshotConfig);
        var readinessGate = StorageReadinessGate.storageReadinessGate();
        var demotionManager = DemotionManager.demotionManager(tiers, metadataStore, DemotionConfig.demotionConfig());
        var garbageCollector = StorageGarbageCollector.storageGarbageCollector(instance,
                                                                               metadataStore,
                                                                               GarbageCollectorConfig.garbageCollectorConfig());

        restoreAndSignalReady(STREAMS_NAME, snapshotManager, metadataStore, readinessGate);
        log.info("Storage 'streams' created: {} tier(s), data dir={}", tiers.size(), snapshotDir.getParent());

        return StorageSetup.storageSetup(STREAMS_NAME,
                                         instance,
                                         snapshotManager,
                                         readinessGate,
                                         metadataStore,
                                         demotionManager,
                                         garbageCollector);
    }

    private static Result<StorageSetup> createOne(String name,
                                                  StorageConfig config,
                                                  String nodeId,
                                                  Option<DHTClient> dhtClient,
                                                  Option<EncryptionKeyring> keyring) {
        // #253 — the per-instance `[storage.<name>] encrypted` flag (not the presence of `keyring`
        // itself) decides whether THIS instance gets wrapped; other instances may share the same
        // node-wide keyring while staying plaintext.
        var effectiveKeyring = config.encrypted()
                               ? keyring
                               : Option.<EncryptionKeyring> empty();
        // #253 BLOCKING #1: name the failing instance in the cause itself (not just in a log line)
        // so `createAll`'s aggregate failure -- and whatever aborts boot on it -- can report which
        // instance failed and why without re-deriving it from call-site context.
        return buildTiers(name, config, dhtClient, effectiveKeyring).mapError(cause -> Causes.cause("Failed to create storage '" + name
                                                                                                   + "': " + cause.message(),
                                                                                                    Option.some(cause)))
                         .map(tiers -> assembleSetup(name, tiers, config, nodeId));
    }

    private static Result<List<StorageTier>> buildTiers(String name,
                                                        StorageConfig config,
                                                        Option<DHTClient> dhtClient,
                                                        Option<EncryptionKeyring> keyring) {
        var memoryTier = MemoryTier.memoryTier(config.memoryMaxBytes());
        var dhtKeyPrefix = name + "-blocks";
        var diskPath = Path.of(config.diskPath());

        return LocalDiskTier.localDiskTier(diskPath,
                                           config.diskMaxBytes())
                            .fold(cause -> handleDiskTierUnavailable(name, cause, memoryTier, dhtClient, dhtKeyPrefix, keyring),
                                  disk -> buildTierList(name, memoryTier, disk, diskPath, dhtClient, dhtKeyPrefix, keyring));
    }

    /// Builds (from `dhtClient`/`dhtKeyPrefix`) and wraps the DHT tier under `keyring` when present
    /// -- shared between the disk-available and disk-unavailable paths so a keyring's coverage
    /// doesn't silently shrink to "disk only" when the disk tier degrades to the memory+DHT
    /// fallback.
    ///
    /// #253 SHOULD-FIX #1 (2026-09-04 ruling): the encrypting branch also writes the per-instance
    /// DHT marker (same [EncryptingStorageTier#MARKER_FILE_NAME] key name as the disk marker, under
    /// this namespace's `dhtKeyPrefix`) mirroring [EncryptingStorageTier#wrapLocalDisk] -- so a LATER
    /// boot with the disk tier absent/wiped and no keyring can be refused by
    /// [#refuseIfDhtEncryptedWithoutKeyring] instead of this method silently handing back the bare
    /// DHT tier. Unlike the disk marker, the write here is unconditional (idempotent put on every
    /// encrypting boot, not a first-enable-only write guarded by an empty-directory check): the DHT
    /// has no directory to scan for pre-existing plaintext, so there is no "empty vs already-has-
    /// blocks" branch to gate on, and re-asserting the marker keeps it current with the latest
    /// active key id.
    private static Result<Option<StorageTier>> maybeEncryptDht(Option<DHTClient> dhtClient,
                                                               String dhtKeyPrefix,
                                                               Option<EncryptionKeyring> keyring) {
        return dhtClient.fold(() -> Result.success(Option.<StorageTier> empty()),
                             client -> {
                                 var dht = DhtStorageTier.dhtStorageTier(client, dhtKeyPrefix);

                                 return keyring.fold(() -> Result.success(Option.some((StorageTier) dht)),
                                                    ring -> writeDhtMarker(client, dhtKeyPrefix, ring).map(_ -> Option.some((StorageTier) EncryptingStorageTier.wrap(dht,
                                                                                                                                                                    ring))));
                             });
    }

    private static Result<Unit> writeDhtMarker(DHTClient client, String dhtKeyPrefix, EncryptionKeyring ring) {
        return client.put(dhtKeyPrefix + "/" + EncryptingStorageTier.MARKER_FILE_NAME,
                          ring.activeKeyId().getBytes(StandardCharsets.UTF_8))
                     .await(DHT_MARKER_TIMEOUT);
    }

    /// #253 SHOULD-FIX #1 (2026-09-04 ruling): the DHT-namespace reverse direction of
    /// [#maybeEncryptDht]'s marker write, mirroring
    /// [EncryptingStorageTier#refuseIfEncryptedWithoutKeyring] for local disk. An absent marker
    /// means this DHT namespace was never encrypted and a bare tier is legitimate; its presence
    /// means blocks under `dhtKeyPrefix` are ciphertext, and a bare tier over them would silently
    /// hand back framed `AEC1...` bytes as content on every read.
    private static Result<Unit> refuseIfDhtEncryptedWithoutKeyring(Option<DHTClient> dhtClient,
                                                                   String dhtKeyPrefix,
                                                                   String instanceName) {
        return dhtClient.fold(() -> Result.success(unit()),
                             client -> client.get(dhtKeyPrefix + "/" + EncryptingStorageTier.MARKER_FILE_NAME)
                                             .flatMap(marker -> marker.fold(() -> Promise.success(unit()),
                                                                            bytes -> Promise.failure(new EncryptionError.EncryptedTierRequiresKeyring(instanceName,
                                                                                                                                                       new String(bytes,
                                                                                                                                                                  StandardCharsets.UTF_8)))))
                                             .await(DHT_MARKER_TIMEOUT));
    }

    private static Result<List<StorageTier>> handleDiskTierUnavailable(String name,
                                                                       Cause cause,
                                                                       MemoryTier memoryTier,
                                                                       Option<DHTClient> dhtClient,
                                                                       String dhtKeyPrefix,
                                                                       Option<EncryptionKeyring> keyring) {
        log.warn("Disk tier for '{}' unavailable: {}, using memory + DHT fallback", name, cause.message());

        var dhtResult = maybeEncryptDht(dhtClient, dhtKeyPrefix, keyring);

        return keyring.fold(() -> refuseIfDhtEncryptedWithoutKeyring(dhtClient, dhtKeyPrefix, name).flatMap(_ -> dhtResult),
                            _ -> dhtResult)
                      .map(dht -> dht.map(t -> List.<StorageTier> of(memoryTier, t))
                                    .or(List.of(memoryTier)));
    }

    /// #253 BLOCKING #3 (2026-09-04 ruling): the no-keyring branch used to return the bare, unwrapped
    /// `diskTier` unconditionally -- silently handing back framed `AEC1...` bytes as plaintext on
    /// every read if `diskPath` was previously encrypted (marker present) and this boot supplies no
    /// keyring for it (`encrypted = false`, or `[storage.encryption]` removed entirely). Checks
    /// [EncryptingStorageTier#refuseIfEncryptedWithoutKeyring] first and fails the instance rather
    /// than reaching the bare-tier branch.
    ///
    /// #253 SHOULD-FIX #1 (2026-09-04 ruling): same guard extended to the DHT tier via
    /// [#refuseIfDhtEncryptedWithoutKeyring] -- the disk-side check alone left the DHT tier's own
    /// marker unchecked when disk happened to be present too.
    private static Result<List<StorageTier>> buildTierList(String name,
                                                           MemoryTier memoryTier,
                                                           LocalDiskTier diskTier,
                                                           Path diskPath,
                                                           Option<DHTClient> dhtClient,
                                                           String dhtKeyPrefix,
                                                           Option<EncryptionKeyring> keyring) {
        var dhtResult = maybeEncryptDht(dhtClient, dhtKeyPrefix, keyring);

        return keyring.fold(() -> EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(diskPath, name)
                                                       .flatMap(_ -> refuseIfDhtEncryptedWithoutKeyring(dhtClient, dhtKeyPrefix, name))
                                                       .flatMap(_ -> dhtResult)
                                                       .map(dht -> dht.map(t -> List.<StorageTier> of(memoryTier, diskTier, t))
                                                                     .or(List.of(memoryTier, diskTier))),
                            ring -> EncryptingStorageTier.wrapLocalDisk(diskTier, diskPath, ring)
                                                         .flatMap(encDisk -> dhtResult.map(dht -> dht.map(t -> List.<StorageTier> of(memoryTier, encDisk, t))
                                                                                                     .or(List.of(memoryTier, encDisk)))));
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
        var demotionManager = DemotionManager.demotionManager(tiers, metadataStore, DemotionConfig.demotionConfig());
        var garbageCollector = StorageGarbageCollector.storageGarbageCollector(instance,
                                                                               metadataStore,
                                                                               GarbageCollectorConfig.garbageCollectorConfig());

        restoreAndSignalReady(name, snapshotManager, metadataStore, readinessGate);
        log.info("Storage '{}' created: {} tier(s), snapshot path={}", name, tiers.size(), config.snapshotPath());

        return StorageSetup.storageSetup(name,
                                         instance,
                                         snapshotManager,
                                         readinessGate,
                                         metadataStore,
                                         demotionManager,
                                         garbageCollector);
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
        return TimeSpan.timeSpan(interval)
                       .map(TimeSpan::toMillis)
                       .or(60_000L);
    }
}
