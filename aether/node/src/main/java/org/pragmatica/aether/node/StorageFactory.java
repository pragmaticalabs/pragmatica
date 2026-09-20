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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
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

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class StorageFactory {
    private static final Logger log = LoggerFactory.getLogger(StorageFactory.class);
    static final String STREAMS_NAME = "streams";
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
    /// [#writeDhtMarker]/[#refuseIfDhtEncryptedWithoutKeyring] perform, bridging into `DHTClient`'s
    /// `Promise`-based API. Same value as `StorageEncryption.RESOLUTION_TIMEOUT`: a hung round trip must
    /// end, not hang. #1052: it bounds ONE attempt ([#attemptDhtMarker]); a timed-out attempt is retried
    /// on [#DHT_MARKER_RETRY_BACKOFF], never fatal. Named `org.pragmatica.lang.io.TimeSpan` in full
    /// because this file already imports the unrelated `org.pragmatica.lang.parse.TimeSpan` under the
    /// simple name.
    private static final org.pragmatica.lang.io.TimeSpan DHT_MARKER_TIMEOUT = timeSpan(30).seconds();

    /// #1052: delay between marker-check attempts that could not complete: 1 s, doubling, capped at
    /// 30 s, jittered. It starts fast because a joining node's ring usually converges within seconds. The
    /// 30 s cap keeps a node that has waited out a long convergence at most one cap behind the ring once
    /// it answers. Jitter spreads replacements that joined together.
    ///
    /// No give-up bound, deliberately. Giving up could only mean exiting, and exiting is not safer than
    /// waiting: the tier stays gated (#858 C1/#874) and the node stays not-ready, so a node that never
    /// verifies serves nothing DHT-backed and says so on `/health/ready`. Exiting would only take a
    /// node out of a cluster that is already short (#1052's docker repro). The one outcome that
    /// warrants stopping is a definite refusal, and that is a [Cause.Terminal] cause, not a count.
    private static final BackoffStrategy DHT_MARKER_RETRY_BACKOFF = BackoffStrategy.exponential()
                                                                                   .initialDelay(timeSpan(1).seconds())
                                                                                   .maxDelay(timeSpan(30).seconds())
                                                                                   .factor(2.0)
                                                                                   .withJitter();

    /// #1052: the stop signal for callers that own no node lifecycle (tests, [#verifyDhtMarker]'s
    /// two-argument entry point). `AetherNode` passes its `PeriodicTasks` cancellation instead.
    private static final BooleanSupplier NEVER_STOPPED = () -> false;

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

        /// #1052: whether this instance's DHT tier is still waiting on its post-formation
        /// encryption-marker check, which retries while the DHT cannot answer. `false` for an instance
        /// with no DHT tier, and for a check that has finished either way. A refused check fails
        /// `start()`, so it never leaves a node reporting ready.
        public boolean dhtAdmissionPending() {
            return dhtMarkerCheck.map(check -> !check.readGate()
                                                     .isResolved())
                                 .or(false);
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
    ///
    /// #852: `armedDisk` is the encrypted local-disk tier whose `.encryption-enabled` marker is NOT
    /// yet written — [#createAll] commits every instance's marker only after every instance's guard
    /// has passed, so a boot refused by one instance stamps no sibling's directory.
    private record TierBuild(List<StorageTier> tiers,
                             Option<DhtMarkerCheck> dhtMarkerCheck,
                             Option<EncryptingStorageTier.ArmedLocalDisk> armedDisk) {
        private TierBuild(List<StorageTier> tiers, Option<DhtMarkerCheck> dhtMarkerCheck) {
            this(tiers, dhtMarkerCheck, Option.empty());
        }

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
        return createAll(configs, nodeId, dhtClient, keyring, StorageConfig.storageConfig());
    }

    /// #1276: [#createAll(Map, String, Option, Option)] with the `defaults` that the synthesized `artifacts`
    /// and `content` instances derive from supplied by the caller, instead of [StorageConfig#storageConfig()]
    /// with its machine-global `/data/aether/...` paths. Tests pass defaults rooted in a per-test directory, so
    /// no test reads or writes another run's storage (an encryption marker left there by one run made every
    /// later keyring-less run refuse to boot). Production callers never use it.
    static Result<Map<String, StorageSetup>> createAll(Map<String, StorageConfig> configs,
                                                       String nodeId,
                                                       Option<DHTClient> dhtClient,
                                                       Option<EncryptionKeyring> keyring,
                                                       StorageConfig defaults) {
        return admit(pendingSetups(configs, nodeId, dhtClient, keyring, defaults));
    }

    /// #852 round 2: the boot decision `AetherNode` actually makes -- the config-map instances AND
    /// `streams`. `streams` used to be a SECOND call after this one ([#defaultStreamStorage]'s
    /// four-argument overload), outside the two-phase commit, so its guard refusing a boot orphaned
    /// every marker this method had just written: the node never started, and backing those
    /// instances out to `encrypted = false` then tripped their own reverse guard on a stamp no
    /// ciphertext justified -- #852's symptom, one call later. Arming `streams` alongside the rest
    /// puts every marker under ONE admission, so a refusal on any arm stamps no directory at all.
    ///
    /// `streams` carries no [StorageConfig] of its own (`[storage.encryption] streams_encrypted` is
    /// a dedicated top-level flag), which is why it arrives as a [StreamSetupRequest] rather than as
    /// another entry in `configs`; the returned map still keys it under `streams` like any other
    /// instance.
    static Result<Map<String, StorageSetup>> createAll(Map<String, StorageConfig> configs,
                                                       String nodeId,
                                                       Option<DHTClient> dhtClient,
                                                       Option<EncryptionKeyring> keyring,
                                                       StreamSetupRequest streams) {
        var results = pendingSetups(configs, nodeId, dhtClient, keyring, StorageConfig.storageConfig());

        results.add(armStreamStorage(streams));

        return admit(results);
    }

    private static List<Result<PendingSetup>> pendingSetups(Map<String, StorageConfig> configs,
                                                            String nodeId,
                                                            Option<DHTClient> dhtClient,
                                                            Option<EncryptionKeyring> keyring,
                                                            StorageConfig defaults) {
        var results = new ArrayList<Result<PendingSetup>>();

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
                                  defaultArtifactsConfig(defaults, keyring.isPresent()),
                                  nodeId,
                                  dhtClient,
                                  keyring));
        }
        // #783 ruling (2026-09-04): `content` -- the shared per-node StorageInstance ContentStore
        // resources provision through (registered as an SPI extension in AetherNode) -- used to be
        // built by a separate, keyring-less `defaultContentStorage` entirely outside this map: no
        // MetadataStore, no DemotionManager/StorageGarbageCollector, so `StorageMaintenanceDriver`
        // (fanned out over `storageSetups`, see #250/#803) never ticked it, and it could never be
        // encrypted regardless of `[storage.encryption]`. Synthesizing it here, exactly like the
        // `artifacts` branch above, routes it through the SAME config-aware, keyring-aware
        // `createOne` path: real demotion/GC, and `encrypted = keyring.isPresent()` unless an
        // explicit `[storage.content]` section overrides it.
        if (!configs.containsKey(CONTENT_NAME)) {
            results.add(createOne(CONTENT_NAME,
                                  defaultContentConfig(configs, defaults, keyring.isPresent()),
                                  nodeId,
                                  dhtClient,
                                  keyring));
        }

        return results;
    }

    /// #852: two phases. Every arm above was built with its disk marker still pending (a pure guard
    /// pass that can refuse); only once the whole set is admitted are the markers written, so a boot
    /// refused by any arm leaves no sibling's directory stamped — the stamp that sibling's own
    /// reverse guard would otherwise refuse on after a back-out.
    private static Result<Map<String, StorageSetup>> admit(List<Result<PendingSetup>> results) {
        return Result.firstFailureOf(results)
                     .flatMap(StorageFactory::commitDiskMarkers)
                     .map(setups -> setups.stream()
                                          .collect(Collectors.toMap(StorageSetup::name,
                                                                    Function.identity())));
    }

    /// The side-effect phase of [#createAll]. A marker write that itself fails still fails the boot;
    /// markers committed before it in the same pass stay. No guard refusal reaches that state -- every
    /// guard has already passed by the time this runs -- so it takes an I/O failure on a marker file or
    /// a crash part-way through the pass. Re-running the same config completes the set: the stamped
    /// instances' guards short-circuit on marker-present and the unstamped ones re-arm over their still
    /// empty directories.
    private static Result<List<StorageSetup>> commitDiskMarkers(List<PendingSetup> pending) {
        return Result.allOf(pending.stream().map(PendingSetup::commitDiskMarker).toList()).map(_ -> pending.stream()
                                                                                                           .map(PendingSetup::setup)
                                                                                                           .toList());
    }

    /// #852: a fully assembled [StorageSetup] plus the disk-marker write [#createAll] has not yet
    /// performed for it.
    private record PendingSetup(StorageSetup setup, Option<EncryptingStorageTier.ArmedLocalDisk> armedDisk) {
        private Result<Unit> commitDiskMarker() {
            return armedDisk.map(disk -> disk.commitMarker()
                                             .mapError(cause -> Causes.cause("Failed to create storage '" + setup.name()
                                                                            + "': " + cause.message(),
                                                                             Option.some(cause))))
                            .or(Result.success(unit()));
        }
    }

    /// #253: the synthesis `defaults` (in production `StorageConfig.storageConfig()`, #1276) with
    /// `encrypted` overridden to track node-wide keyring presence, for the synthesized default
    /// `artifacts` instance in [#createAll] -- see the ruling note there.
    private static StorageConfig defaultArtifactsConfig(StorageConfig defaults, boolean encrypted) {
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
    private static final String CONTENT_NAME = "content";

    /// #783: the synthesis `defaults` (in production `StorageConfig.storageConfig()`, #1276) with
    /// `encrypted` tracking node-wide keyring presence (mirroring [#defaultArtifactsConfig]), but
    /// `diskPath`/`snapshotPath` are NOT the bare defaults -- `assembleSetup` reads
    /// `config.snapshotPath()` directly with no per-instance subdirectory of its own, so reusing the
    /// artifacts default verbatim would collide both instances' snapshot files (and disk blocks) in
    /// the same directory. Instead this derives a
    /// `content` data dir as a SIBLING of wherever `artifacts` actually resolves -- the explicit
    /// `[storage.artifacts]` config when the operator set one, else the hardcoded default -- then splits
    /// it into `content/blocks` (disk) and `content/snapshots` (metadata) so neither collides with
    /// artifacts' own paths or with each other.
    ///
    /// This borrows the "sibling of the artifacts disk path, then subdivided" SHAPE from
    /// `AetherNode.streamDataDir`, but it is deliberately NOT the same convention, and the difference
    /// matters: `streamDataDir` appends `.resolve(config.self().id())`, a node-id segment this does
    /// not have. That segment is what keeps two nodes sharing one host mount from writing the same
    /// directory. Without it, co-located nodes share `<artifacts>/../content/{blocks,snapshots}`:
    /// blocks are content-addressed so concurrent writes are benign, but one node's GC
    /// (`deleteFromPrivateTiers`) can delete a file the other's `MetadataStore` still records as
    /// present, and `LocalDiskTier.calculateUsedBytes()`'s directory walk double-counts across them.
    /// Not fixed here because `artifacts` has had exactly this shape since before #783 (its default
    /// `/data/aether/storage` carries no node id either), so adding the segment for `content` alone
    /// would leave the pair inconsistent and change on-disk layout for a case #783 did not create.
    ///
    /// The GENERAL version of the collision hazard -- any two EXPLICITLY configured instances that
    /// both omit `disk_path`/`snapshot_path` still share the bare `StorageConfig.storageConfig()`
    /// default and collide -- is likewise not addressed here; see the PR body.
    private static StorageConfig defaultContentConfig(Map<String, StorageConfig> configs,
                                                      StorageConfig defaults,
                                                      boolean encrypted) {
        var artifactsConfig = option(configs.get(ARTIFACTS_NAME)).or(defaults);
        var contentDataDir = Path.of(artifactsConfig.diskPath()).resolveSibling(CONTENT_NAME);

        return new StorageConfig(defaults.memoryMaxBytes(),
                                 defaults.diskMaxBytes(),
                                 contentDataDir.resolve("blocks").toString(),
                                 contentDataDir.resolve("snapshots").toString(),
                                 defaults.snapshotMutationThreshold(),
                                 defaults.snapshotMaxInterval(),
                                 defaults.snapshotRetentionCount(),
                                 defaults.walPath(),
                                 encrypted);
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
    ///
    /// #852 round 2: the standalone entry -- arm, then commit this one marker immediately, which is
    /// what a caller deciding nothing else wants. `AetherNode` does NOT use it; it hands the same
    /// parameters to [#createAll] as a [StreamSetupRequest] so the segments marker is committed under
    /// the same admission as every other instance's. Nothing in the type system prevents a future
    /// caller from reintroducing the second call -- what refuses that is
    /// `StorageFactoryEncryptionTest#bootDecision_leavesNoDiskMarkerOnAnInstance_whenTheStreamsArmRefusesOverExistingPlaintext`
    /// and its reverse-direction sibling, which drive the production entry point and would go red.
    static Result<StorageSetup> defaultStreamStorage(Option<DHTClient> dhtClient,
                                                     Path streamDataDir,
                                                     String nodeId,
                                                     Option<EncryptionKeyring> keyring) {
        return armStreamStorage(new StreamSetupRequest(dhtClient, streamDataDir, nodeId, keyring)).flatMap(pending -> pending.commitDiskMarker()
                                                                                                                             .map(_ -> pending.setup()));
    }

    /// #852: everything [#defaultStreamStorage] does except the marker write -- the `streams`
    /// counterpart of [#createOne], so [#createAll] can hold its side effect back until every arm
    /// has been admitted. Both guard directions live here unchanged: `refuseIfEncryptedWithoutKeyring`
    /// when `streams_encrypted` is off over a marked segments dir, `armLocalDisk` when it is on over
    /// a segments dir that already holds plaintext.
    private static Result<PendingSetup> armStreamStorage(StreamSetupRequest request) {
        var segmentsDir = request.streamDataDir().resolve("segments");
        var snapshotDir = request.streamDataDir().resolve("snapshots");

        return request.keyring()
                      .fold(() -> EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(segmentsDir, STREAMS_NAME).map(_ -> new PendingSetup(defaultStreamStorage(request.dhtClient(),
                                                                                                                                                                  request.streamDataDir(),
                                                                                                                                                                  request.nodeId()),
                                                                                                                                             Option.none())),
                            ring -> armEncryptedStreamTiers(request.dhtClient(),
                                                            segmentsDir,
                                                            ring).map(build -> new PendingSetup(assembleStreamSetup(build.tiers(),
                                                                                                                    snapshotDir,
                                                                                                                    request.nodeId()),
                                                                                                build.armedDisk())));
    }

    /// #852: the `streams` parameters `AetherNode` resolves for itself -- `streams_encrypted` has no
    /// per-instance [StorageConfig] to carry it -- gathered so [#createAll] can take them as one
    /// argument. `keyring` is that already-resolved decision, empty when streams is not encrypted.
    record StreamSetupRequest(Option<DHTClient> dhtClient,
                              Path streamDataDir,
                              String nodeId,
                              Option<EncryptionKeyring> keyring) {}

    private static Result<TierBuild> armEncryptedStreamTiers(Option<DHTClient> dhtClient,
                                                             Path segmentsDir,
                                                             EncryptionKeyring keyring) {
        var memoryTier = MemoryTier.memoryTier(STREAM_MEMORY_BYTES);
        var dhtTier = dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "stream-segments"))
                               .map(dht -> EncryptingStorageTier.wrap(dht, keyring));

        return LocalDiskTier.localDiskTier(segmentsDir, STREAM_DISK_BYTES).fold(cause -> {
                                                                                    log.warn("Disk tier for 'streams' unavailable: {}, using memory + DHT fallback",
                                                                                             cause.message());

                                                                                    return Result.success(new TierBuild(dhtTier.map(dht -> List.<StorageTier> of(memoryTier,
                                                                                                                                                                 dht))
                                                                                                                               .or(List.of(memoryTier)),
                                                                                                                        Option.none()));
                                                                                },
                                                                                disk -> EncryptingStorageTier.armLocalDisk(disk,
                                                                                                                           segmentsDir,
                                                                                                                           keyring).map(armed -> new TierBuild(dhtTier.map(dht -> List.<StorageTier> of(memoryTier,
                                                                                                                                                                                                        armed.tier(),
                                                                                                                                                                                                        dht))
                                                                                                                                                                      .or(List.of(memoryTier,
                                                                                                                                                                                  armed.tier())),
                                                                                                                                                               Option.none(),
                                                                                                                                                               Option.some(armed))));
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

    private static Result<PendingSetup> createOne(String name,
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
                         .map(build -> new PendingSetup(assembleSetup(name,
                                                                      build.tiers(),
                                                                      config,
                                                                      nodeId,
                                                                      build.dhtMarkerCheck()),
                                                        build.armedDisk()));
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
    ///
    /// #1052: a check that cannot complete is retried, not failed. Each [#attemptDhtMarker] that ends in a
    /// non-terminal cause (`EncryptionError.DhtMarkerCheckTimedOut`, `DHTError.QuorumNotReached`,
    /// `DHTError.PeerUnreachable`, ...) is retried on [#DHT_MARKER_RETRY_BACKOFF] with no attempt budget.
    /// Only a [Cause.Terminal] cause ends the check. So `readGate` is resolved by the FINAL outcome
    /// alone. It stays pending across failed attempts, so the tier stays gated. It must not be resolved
    /// per attempt: it is first-writer-wins, so a timed-out first attempt would refuse the tier forever,
    /// even after a later attempt verified it.
    static Promise<Unit> verifyDhtMarker(DHTClient client, DhtMarkerCheck check) {
        return verifyDhtMarker(client, check, DHT_MARKER_TIMEOUT, DHT_MARKER_RETRY_BACKOFF, NEVER_STOPPED);
    }

    /// #1052: the retrying check with every knob explicit. The two-argument [#verifyDhtMarker] and
    /// [#verifyDhtMarkers] fix the production bound and cadence. Package-private so a test can shrink
    /// them to milliseconds and drive `stopped`. `stopped` is read before every attempt. Once it reports
    /// `true`, the next attempt fails with the terminal [EncryptionError.DhtMarkerCheckAbandoned] instead
    /// of touching the DHT, so the loop ends. core `Retry` keeps scheduling after its output resolves, so
    /// cancelling the returned promise alone would not stop it.
    static Promise<Unit> verifyDhtMarker(DHTClient client,
                                         DhtMarkerCheck check,
                                         org.pragmatica.lang.io.TimeSpan attemptTimeout,
                                         BackoffStrategy backoff,
                                         BooleanSupplier stopped) {
        var attempts = new AtomicInteger();

        return Retry.retry()
                    .attempts(Integer.MAX_VALUE)
                    .strategy(backoff)
                    .execute(() -> attemptUnlessStopped(client,
                                                        check,
                                                        attemptTimeout,
                                                        stopped,
                                                        attempts.incrementAndGet()))
                    .onResult(check.readGate()::resolve);
    }

    private static Promise<Unit> attemptUnlessStopped(DHTClient client,
                                                      DhtMarkerCheck check,
                                                      org.pragmatica.lang.io.TimeSpan attemptTimeout,
                                                      BooleanSupplier stopped,
                                                      int attempt) {
        return stopped.getAsBoolean()
               ? new EncryptionError.DhtMarkerCheckAbandoned(check.instanceName()).promise()
               : attemptAndReport(client, check, attemptTimeout, attempt);
    }

    /// #1052: one WARN per failed attempt, naming the instance and the attempt number (1-based, counted
    /// per [#verifyDhtMarker] call -- core `Retry` does not hand its attempt index to the operation). The
    /// rate is bounded by [#DHT_MARKER_RETRY_BACKOFF]: at most one line per 30 s per instance once the
    /// backoff has reached its cap. core `Retry` logs per-attempt progress only at DEBUG (#718), which
    /// would leave a node that is not ready silent at the default level. Emitted through this class's
    /// SLF4J logger (onto log4j2 in the node), not `System.Logger` (#1077), so it reaches the node's
    /// appenders and `StorageFactoryDhtMarkerRetryTest` can capture it.
    private static Promise<Unit> attemptAndReport(DHTClient client,
                                                  DhtMarkerCheck check,
                                                  org.pragmatica.lang.io.TimeSpan attemptTimeout,
                                                  int attempt) {
        return attemptDhtMarker(client, check, attemptTimeout).onFailure(cause -> log.warn("DHT encryption-marker check attempt {} for instance '{}' did not complete: {} "
                                                                                          + "(operations on its DHT tier stay gated and the node stays not-ready until the check completes)",
                                                                                           attempt,
                                                                                           check.instanceName(),
                                                                                           cause.message()));
    }

    /// #858 C2 test seam, renamed from the three-argument `verifyDhtMarker` by #1052: ONE marker get/put
    /// attempt, bounded by `timeout`, which neither retries nor touches `readGate`. Lets a test prove "a
    /// never-resolving DHT client yields the timeout cause" in milliseconds, mirroring
    /// `MavenProtocolRoutesTimeoutTest`'s injected `SHORT_TIMEOUT`. Production reaches it only through
    /// [#verifyDhtMarker], at [#DHT_MARKER_TIMEOUT].
    static Promise<Unit> attemptDhtMarker(DHTClient client,
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
                                                 timeout));
    }

    /// #858: fans [#verifyDhtMarker] across every check in `checks` -- called once, post-formation,
    /// from `AetherNode.start()`, generic over `storageSetups`' CONTENTS (no hardcoded instance
    /// names), so an instance that starts carrying a DHT tier later (#783: `content` routed through
    /// `createAll`) is covered automatically. Cancels the remaining in-flight checks on the first
    /// failure (`allOfOrCancel`) since one failure aborts `start()` and stops the node regardless of
    /// the others' outcome.
    ///
    /// #1052: every check retries until it completes, so the only failure that reaches `allOfOrCancel` is
    /// a terminal one. `stopped` is the owning node's stop signal (`PeriodicTasks#isCancelled`). A
    /// cancelled sibling's output promise resolves at once, but its retry loop keeps attempting until
    /// `stopped` fires, because core `Retry` does not observe its output. On a refusal that happens
    /// immediately: `Main#exitWithError` in production, `abortStart`'s stop in Ember.
    static Promise<Unit> verifyDhtMarkers(DHTClient client, List<DhtMarkerCheck> checks, BooleanSupplier stopped) {
        if (checks.isEmpty()) {
            return Promise.UNIT;
        }

        var verifications = checks.stream()
                                  .map(check -> verifyDhtMarker(client,
                                                                check,
                                                                DHT_MARKER_TIMEOUT,
                                                                DHT_MARKER_RETRY_BACKOFF,
                                                                stopped))
                                  .toList();

        return Promise.allOfOrCancel(verifications).flatMap(results -> Result.firstFailureOf(results).fold(cause -> Promise.<Unit> failure(cause),
                                                                                                           _ -> Promise.UNIT));
    }

    /// #1052: succeeds once EVERY DHT-backed instance in `setups` has been admitted by its
    /// post-formation marker check, with no DHT-backed instance counting as admitted at once. Fails if
    /// any check refused (or was abandoned by a stop). Built from the same `readGate`s the tiers wait
    /// on, so "reported ready" and "DHT tier admitted" cannot disagree. `AetherNode` defers the NDM
    /// self-ready signal (lifecycle ACTIVE, reported READY) on it.
    static Promise<Unit> dhtAdmission(Map<String, StorageSetup> setups) {
        var gates = setups.values()
                          .stream()
                          .map(StorageSetup::dhtMarkerCheck)
                          .flatMap(Option::stream)
                          .map(DhtMarkerCheck::readGate)
                          .toList();

        return Promise.allOf(gates)
                      .flatMap(results -> Result.allOf(results).async())
                      .mapToUnit();
    }

    /// #1052: names, sorted, of the instances in `setups` whose DHT marker check is still pending --
    /// the `dht-admission` readiness component's detail (`StatusRoutes`).
    public static List<String> pendingDhtAdmissions(Map<String, StorageSetup> setups) {
        return setups.entrySet()
                     .stream()
                     .filter(entry -> entry.getValue()
                                           .dhtAdmissionPending())
                     .map(Map.Entry::getKey)
                     .sorted()
                     .toList();
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
    /// means the attempt never learned whether a marker exists, so it fails on THIS cause (#1052: and
    /// is retried)
    /// ([EncryptionError.DhtMarkerCheckTimedOut]) -- never [EncryptionError.EncryptedTierRequiresKeyring],
    /// which means the opposite: the marker WAS read successfully, it is present, and no keyring is
    /// configured for the instance ([#refuseIfDhtEncryptedWithoutKeyring] -- the only branch that raises
    /// it here; it compares nothing against a keyring, and with a keyring configured the marker is
    /// overwritten unread, #831).
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
                            ring -> EncryptingStorageTier.armLocalDisk(diskTier, diskPath, ring).map(armed -> withDht(maybeEncryptDht(name,
                                                                                                                                      dhtClient,
                                                                                                                                      dhtKeyPrefix,
                                                                                                                                      keyring),
                                                                                                                      List.of(memoryTier,
                                                                                                                              armed.tier()),
                                                                                                                      Option.some(armed))));
    }

    private static TierBuild withDht(TierBuild.DhtBuild dhtBuild, List<StorageTier> baseTiers) {
        return withDht(dhtBuild, baseTiers, Option.empty());
    }

    private static TierBuild withDht(TierBuild.DhtBuild dhtBuild,
                                     List<StorageTier> baseTiers,
                                     Option<EncryptingStorageTier.ArmedLocalDisk> armedDisk) {
        var tiers = dhtBuild.tier()
                            .map(dht -> {
                                     var withDht = new ArrayList<>(baseTiers);

                                     withDht.add(dht);

                                     return List.<StorageTier> copyOf(withDht);
                                 })
                            .or(baseTiers);

        return new TierBuild(tiers, dhtBuild.markerCheck(), armedDisk);
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
        // #1012: the restored epoch has to reach the store, not just the log line below. The two
        // calls above only INCREMENT a store that starts at zero, so without this the epoch
        // restarted near zero on every boot and `DefaultSnapshotManager` wrote the next snapshot
        // under a file name lower than every retained predecessor. Applied last, because both
        // restore calls bump the epoch themselves.
        metadataStore.restoreEpoch(snapshot.epoch());
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
