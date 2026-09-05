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
import org.pragmatica.lang.io.CoreError;
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
                               StorageGarbageCollector garbageCollector,
                               Option<DhtMarkerCheck> dhtMarkerCheck) {
        /// #858: pre-#858 call sites that build a `StorageSetup` with no DHT tier involved (e.g.
        /// `StorageRoutesTest`'s direct `new StorageSetup(...)`) keep compiling unchanged, delegating
        /// to the canonical constructor with an empty marker check.
        public StorageSetup(String name,
                            StorageInstance instance,
                            SnapshotManager snapshotManager,
                            StorageReadinessGate readinessGate,
                            MetadataStore metadataStore,
                            DemotionManager demotionManager,
                            StorageGarbageCollector garbageCollector) {
            this(name,
                 instance,
                 snapshotManager,
                 readinessGate,
                 metadataStore,
                 demotionManager,
                 garbageCollector,
                 Option.empty());
        }

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

        public static StorageSetup storageSetup(String name,
                                                StorageInstance instance,
                                                SnapshotManager snapshotManager,
                                                StorageReadinessGate readinessGate,
                                                MetadataStore metadataStore,
                                                DemotionManager demotionManager,
                                                StorageGarbageCollector garbageCollector,
                                                Option<DhtMarkerCheck> dhtMarkerCheck) {
            return new StorageSetup(name,
                                    instance,
                                    snapshotManager,
                                    readinessGate,
                                    metadataStore,
                                    demotionManager,
                                    garbageCollector,
                                    dhtMarkerCheck);
        }
    }

    /// #858: carries what the post-formation step ([#verifyDhtMarkers], called from
    /// `AetherNode.start()`) needs to check/write ONE instance's DHT-namespace encryption marker
    /// without re-deriving it -- the per-instance effective keyring decision `createOne` already made
    /// (`config.encrypted() ? keyring : empty`), the DHT namespace prefix, and the `readGate` that
    /// blocks `DhtStorageTier.get()` for this namespace until [#verifyDhtMarker] resolves it. Present
    /// only for instances that actually carry a DHT tier -- see [#maybeEncryptDht].
    record DhtMarkerCheck(String instanceName,
                          String dhtKeyPrefix,
                          Option<EncryptionKeyring> effectiveKeyring,
                          Promise<Unit> readGate) {}

    /// #858: the tier list plus the (possibly absent) DHT marker check that goes with it -- threaded
    /// from [#maybeEncryptDht] up through [#buildTierList]/[#handleDiskTierUnavailable]/[#buildTiers]
    /// to [#createOne], which hands `dhtMarkerCheck` to `StorageSetup` unchanged.
    private record TierBuild(List<StorageTier> tiers, Option<DhtMarkerCheck> dhtMarkerCheck) {
        /// #858: [#maybeEncryptDht]'s own return shape -- the single (possibly absent) DHT tier plus
        /// its marker check, before either is folded into a [TierBuild]'s full tier list by
        /// [#withDht]. Kept separate from the enclosing record because `maybeEncryptDht` builds at
        /// most one tier, never a list.
        private record DhtBuild(Option<StorageTier> tier, Option<DhtMarkerCheck> markerCheck) {}
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
                         .map(build -> assembleSetup(name,
                                                     build.tiers(),
                                                     config,
                                                     nodeId,
                                                     build.dhtMarkerCheck()));
    }

    private static Result<TierBuild> buildTiers(String name,
                                                StorageConfig config,
                                                Option<DHTClient> dhtClient,
                                                Option<EncryptionKeyring> keyring) {
        var memoryTier = MemoryTier.memoryTier(config.memoryMaxBytes());
        var dhtKeyPrefix = name + "-blocks";
        var diskPath = Path.of(config.diskPath());

        return LocalDiskTier.localDiskTier(diskPath,
                                           config.diskMaxBytes())
                            .fold(cause -> handleDiskTierUnavailable(name,
                                                                     cause,
                                                                     memoryTier,
                                                                     dhtClient,
                                                                     dhtKeyPrefix,
                                                                     keyring),
                                  disk -> buildTierList(name,
                                                        memoryTier,
                                                        disk,
                                                        diskPath,
                                                        dhtClient,
                                                        dhtKeyPrefix,
                                                        keyring));
    }

    /// Builds the DHT tier (from `dhtClient`/`dhtKeyPrefix`) and wraps it under `keyring` when
    /// present -- shared between the disk-available and disk-unavailable paths so a keyring's
    /// coverage doesn't silently shrink to "disk only" when the disk tier degrades to the memory+DHT
    /// fallback. Purely in-memory now (#858): building the tier and its [#DhtMarkerCheck] does no
    /// I/O -- the marker read/write itself is deferred to [#verifyDhtMarker], run post-formation from
    /// `AetherNode.start()`, because the `DHTClient` handed to the constructor cannot route before
    /// cluster formation resolves there. The returned tier is gated on a fresh, unresolved
    /// `readGate`: [DhtStorageTier#get] blocks until [#verifyDhtMarker] resolves it, so no read can
    /// observe a namespace whose marker hasn't been checked yet.
    private static TierBuild.DhtBuild maybeEncryptDht(String name,
                                                      Option<DHTClient> dhtClient,
                                                      String dhtKeyPrefix,
                                                      Option<EncryptionKeyring> keyring) {
        return dhtClient.fold(() -> new TierBuild.DhtBuild(Option.empty(), Option.empty()),
                              client -> {
                                  var readGate = Promise.<Unit> promise();
                                  var dht = DhtStorageTier.dhtStorageTier(client, dhtKeyPrefix, name, readGate);
                                  var tier = keyring.<StorageTier> fold(() -> dht,
                                                                        ring -> EncryptingStorageTier.wrap(dht, ring));
                                  var check = new DhtMarkerCheck(name, dhtKeyPrefix, keyring, readGate);

                                  return new TierBuild.DhtBuild(Option.some(tier), Option.some(check));
                              });
    }

    /// #858: runs post-formation (`AetherNode.start()`, via [#verifyDhtMarkers]) -- replaces the
    /// former boot-time `.await(DHT_MARKER_TIMEOUT)` inside the constructor path (`createAll` ->
    /// `createOne` -> [#maybeEncryptDht]), which blocked on a `DHTClient` that cannot route before
    /// cluster formation resolves and always burned the full 30 s timeout on a real boot with a DHT
    /// client and no keyring (#858). No keyring: refuses if the marker is present -- same
    /// `EncryptionError.EncryptedTierRequiresKeyring` cause as before, just raised later
    /// (fail-closed; `start()` aborts and the node stops). Keyring present: (re)writes the marker.
    ///
    /// #875: BOTH branches resolve `check.readGate()` -- success admits [DhtStorageTier]'s gated
    /// operations immediately; failure resolves the SAME gate WITH the refusal cause, so a caller
    /// racing this check fails with that cause right away instead of waiting out the full
    /// `admissionTimeout` and surfacing the wrong error (`StorageError.TierNotAdmitted`). An earlier
    /// version left the failure branch unresolved, reasoning a failed `start()` aborts the node before
    /// anything could observe it -- true for `start()`'s own chain, but `readGate` is a shared,
    /// resolve-once promise with no guarantee every caller reads it only after that abort completes;
    /// resolving it with the cause removes the race instead of relying on the abort's timing.
    static Promise<Unit> verifyDhtMarker(DHTClient client, DhtMarkerCheck check) {
        return verifyDhtMarker(client, check, DHT_MARKER_TIMEOUT);
    }

    /// #858 C2 test seam: lets a test bound the marker get/put far below the 30s production default,
    /// so "a never-resolving DHT client yields the timeout cause" is provable in milliseconds. Mirrors
    /// `MavenProtocolRoutesTimeoutTest`'s injected `SHORT_TIMEOUT` for the same reason. Package-private
    /// -- only `StorageFactoryEncryptionTest` (same package) needs it; [#verifyDhtMarker] above is the
    /// production entry point, fixed at [#DHT_MARKER_TIMEOUT].
    static Promise<Unit> verifyDhtMarker(DHTClient client,
                                         DhtMarkerCheck check,
                                         org.pragmatica.lang.io.TimeSpan timeout) {
        return check.effectiveKeyring()
                    .fold(() -> refuseIfDhtEncryptedWithoutKeyring(client,
                                                                   check.dhtKeyPrefix(),
                                                                   check.instanceName(),
                                                                   timeout),
                          ring -> writeDhtMarker(client,
                                                 check.dhtKeyPrefix(),
                                                 check.instanceName(),
                                                 ring,
                                                 timeout))
                    .onSuccess(_ -> check.readGate()
                                         .resolve(Result.success(unit())))
                    .onFailure(cause -> check.readGate()
                                             .resolve(Result.failure(cause)));
    }

    /// #858: fans [#verifyDhtMarker] across every check in `checks` -- called once, post-formation,
    /// from `AetherNode.start()`, generic over `storageSetups`' CONTENTS (no hardcoded instance
    /// names), so an instance that starts carrying a DHT tier later (#783: `content` routed through
    /// `createAll`) is covered automatically. Cancels the remaining in-flight checks on the first
    /// failure (`allOfOrCancel`) since one failure aborts `start()` and stops the node regardless of
    /// the others' outcome.
    static Promise<Unit> verifyDhtMarkers(DHTClient client, List<DhtMarkerCheck> checks) {
        if (checks.isEmpty()) {
            return Promise.UNIT;
        }

        var verifications = checks.stream().map(check -> verifyDhtMarker(client, check)).toList();

        return Promise.allOfOrCancel(verifications).flatMap(results -> Result.firstFailureOf(results).fold(cause -> Promise.<Unit> failure(cause),
                                                                                                           _ -> Promise.UNIT));
    }

    private static Promise<Unit> writeDhtMarker(DHTClient client,
                                                String dhtKeyPrefix,
                                                String instanceName,
                                                EncryptionKeyring ring,
                                                org.pragmatica.lang.io.TimeSpan timeout) {
        return client.put(dhtKeyPrefix + "/" + EncryptingStorageTier.MARKER_FILE_NAME,
                          ring.activeKeyId().getBytes(StandardCharsets.UTF_8))
                     .timeout(timeout)
                     .mapError(cause -> remapMarkerTimeout(cause, instanceName, timeout));
    }

    /// #858 C2: `.timeout()` is safe to call directly on these two chains -- unlike
    /// `DhtStorageTier#admission`'s `readGate` -- because `client.put`/`client.get` return a fresh,
    /// single-use, non-shared promise per call; there is no second reader who could observe a
    /// timeout-vs-real-result race on the same promise.
    ///
    /// Two distinct causes, never conflated: a marker get/put that itself times out after formation
    /// means `start()` never learned whether a marker exists, so it fails on THIS cause
    /// ([EncryptionError.DhtMarkerCheckTimedOut]) -- never [EncryptionError.EncryptedTierRequiresKeyring],
    /// which means the opposite: the marker WAS read successfully and named a key id absent from the
    /// configured keyring.
    private static Cause remapMarkerTimeout(Cause cause, String instanceName, org.pragmatica.lang.io.TimeSpan timeout) {
        return cause instanceof CoreError.Timeout
               ? new EncryptionError.DhtMarkerCheckTimedOut(instanceName, timeout.millis())
               : cause;
    }

    /// #858: the DHT-namespace reverse direction of [#writeDhtMarker], mirroring
    /// [EncryptingStorageTier#refuseIfEncryptedWithoutKeyring] for local disk. An absent marker means
    /// this DHT namespace was never encrypted and a bare tier is legitimate; its presence means
    /// blocks under `dhtKeyPrefix` are ciphertext, and a bare tier over them would silently hand back
    /// framed `AEC1...` bytes as content on every read.
    private static Promise<Unit> refuseIfDhtEncryptedWithoutKeyring(DHTClient client,
                                                                    String dhtKeyPrefix,
                                                                    String instanceName,
                                                                    org.pragmatica.lang.io.TimeSpan timeout) {
        return client.get(dhtKeyPrefix + "/" + EncryptingStorageTier.MARKER_FILE_NAME)
                     .flatMap(marker -> marker.fold(() -> Promise.success(unit()),
                                                    bytes -> Promise.<Unit> failure(new EncryptionError.EncryptedTierRequiresKeyring(instanceName,
                                                                                                                                     new String(bytes,
                                                                                                                                                StandardCharsets.UTF_8)))))
                     .timeout(timeout)
                     .mapError(cause -> remapMarkerTimeout(cause, instanceName, timeout));
    }

    private static Result<TierBuild> handleDiskTierUnavailable(String name,
                                                               Cause cause,
                                                               MemoryTier memoryTier,
                                                               Option<DHTClient> dhtClient,
                                                               String dhtKeyPrefix,
                                                               Option<EncryptionKeyring> keyring) {
        log.warn("Disk tier for '{}' unavailable: {}, using memory + DHT fallback", name, cause.message());

        return Result.success(withDht(maybeEncryptDht(name, dhtClient, dhtKeyPrefix, keyring), List.of(memoryTier)));
    }

    /// #858 BLOCKING #3 (unchanged from #253): the no-keyring branch checks
    /// [EncryptingStorageTier#refuseIfEncryptedWithoutKeyring] before reaching the bare-disk-tier
    /// branch -- silently handing back framed `AEC1...` bytes as plaintext would otherwise be
    /// possible if `diskPath` was previously encrypted (marker present) and this boot supplies no
    /// keyring for it. The DHT-side counterpart of that same guard ([#refuseIfDhtEncryptedWithoutKeyring])
    /// no longer runs HERE (#858) -- [#maybeEncryptDht] only builds the (gated) tier and its
    /// [#DhtMarkerCheck]; the actual marker read/refuse runs post-formation, in `AetherNode.start()`.
    private static Result<TierBuild> buildTierList(String name,
                                                   MemoryTier memoryTier,
                                                   LocalDiskTier diskTier,
                                                   Path diskPath,
                                                   Option<DHTClient> dhtClient,
                                                   String dhtKeyPrefix,
                                                   Option<EncryptionKeyring> keyring) {
        return keyring.fold(() -> EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(diskPath, name).map(_ -> withDht(maybeEncryptDht(name,
                                                                                                                                         dhtClient,
                                                                                                                                         dhtKeyPrefix,
                                                                                                                                         keyring),
                                                                                                                         List.of(memoryTier,
                                                                                                                                 diskTier))),
                            ring -> EncryptingStorageTier.wrapLocalDisk(diskTier, diskPath, ring).map(encDisk -> withDht(maybeEncryptDht(name,
                                                                                                                                         dhtClient,
                                                                                                                                         dhtKeyPrefix,
                                                                                                                                         keyring),
                                                                                                                         List.of(memoryTier,
                                                                                                                                 encDisk))));
    }

    private static TierBuild withDht(TierBuild.DhtBuild dhtBuild, List<StorageTier> baseTiers) {
        var tiers = dhtBuild.tier()
                            .map(dht -> {
                                     var withDht = new ArrayList<>(baseTiers);

                                     withDht.add(dht);

                                     return List.<StorageTier> copyOf(withDht);
                                 })
                            .or(baseTiers);

        return new TierBuild(tiers, dhtBuild.markerCheck());
    }

    private static StorageSetup assembleSetup(String name,
                                              List<StorageTier> tiers,
                                              StorageConfig config,
                                              String nodeId,
                                              Option<DhtMarkerCheck> dhtMarkerCheck) {
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
                                         garbageCollector,
                                         dhtMarkerCheck);
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
