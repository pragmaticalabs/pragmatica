// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.PromotionStallWatch;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.StorageMaintenanceDriver;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.storage.DelegatedStorageAdapter;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.METADATA_SNAPSHOT_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.createStreamDirectory;
import static org.pragmatica.aether.node.AetherNode.streamDataDir;
import static org.pragmatica.aether.node.AetherNode.streamWalBaseDir;
import static org.pragmatica.aether.node.AetherNode.walDisabled;


/// The storage unit of the node assembly: the storage setups the node holds (streams, artifacts, content and the
/// configured instances), the artifact store and repositories over them, the tier maintenance driver and the periodic
/// metadata snapshot. Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface StorageAssembly {
    /// What `createStorageSetups` reads from the assembly built so far.
    record CreateStorageSetupsInputs(AetherNodeConfig config,
                                     Option<EncryptionKeyring> storageKeyring,
                                     Option<DHTClient> dhtClientOption) {}

    /// Resolves stream encryption and creates every configured storage setup; the root fails the node boot on a failed creation.
    static Result<Map<String, StorageFactory.StorageSetup>> createStorageSetups(CreateStorageSetupsInputs in) {
        var config = in.config();
        var storageKeyring = in.storageKeyring();
        var dhtClientOption = in.dhtClientOption();
        // #253 BLOCKING #1 (2026-09-04 ruling): a configured storage instance that fails to create
        // is a boot failure -- `createAll` now returns `Result` and this aborts naming the instance
        // and the cause, the same way `streamStorageResult`'s failure is propagated below, rather
        // than silently dropping that one instance (the old behavior) and letting boot continue on
        // whatever was left, e.g. a `wrapLocalDisk` refusal over plaintext artifacts.
        // Stream storage is a first-class, disk-backed snapshot-capable StorageSetup (memory -> disk
        // -> DHT) keyed under "streams". It has no `[storage.X]` section of its own -- `streams_encrypted`
        // is a dedicated top-level `[storage.encryption]` flag -- so its parameters are resolved here
        // and handed to `createAll` as a `StreamSetupRequest`; the returned map carries it under
        // "streams" alongside the config-map instances, and its `instance()` is reused as
        // `streamStorage` at the stream wiring site below.
        //
        // #852 round 2: it is part of the SAME `createAll` call, not a second one after it. As a second
        // call its guard could refuse a boot whose earlier arms had already stamped their directories,
        // leaving markers nothing owns -- see `StorageFactory.createAll`'s five-argument overload.
        var streamsEncrypted = config.storageEncryption().map(StorageEncryptionConfig::streamsEncrypted).or(false);
        var streamsKeyring = streamsEncrypted
                             ? storageKeyring
                             : Option.<EncryptionKeyring> empty();
        var storageSetupsResult = StorageFactory.createAll(config.storageConfig(),
                                                           config.self().id(),
                                                           dhtClientOption,
                                                           storageKeyring,
                                                           new StorageFactory.StreamSetupRequest(dhtClientOption,
                                                                                                 streamDataDir(config),
                                                                                                 config.self().id(),
                                                                                                 streamsKeyring,
                                                                                                 config.streaming()
                                                                                                       .segmentDiskMaxBytes(),
                                                                                                 resolveStreamWalDir(config)));

        return storageSetupsResult;
    }

    /// What `artifactStorage` reads from the assembly built so far.
    record ArtifactStorageInputs(AetherNodeConfig config,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 DistributedDHTClient dhtClient,
                                 Map<String, StorageFactory.StorageSetup> storageSetups) {}

    /// What `artifactStorage` hands back for the rest of the assembly.
    record ArtifactStorageOutputs(StorageFactory.StorageSetup streamStorageSetup,
                                  StorageInstance contentStorage,
                                  ArtifactStore artifactStore,
                                  List<Repository> repositories) {}

    /// The stream, artifact and content storage instances, the artifact store and the repositories over them.
    static ArtifactStorageOutputs artifactStorage(ArtifactStorageInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var dhtClient = in.dhtClient();
        var storageSetups = in.storageSetups();
        // Same invariant as "artifacts" and "content" below: `createAll` builds "streams" itself now,
        // so its absence here would mean the guard above should already have failed.
        var streamStorageSetup = Objects.requireNonNull(storageSetups.get(StorageFactory.STREAMS_NAME),
                                                        "storageSetups missing \"streams\" after createAll succeeded -- invariant violated");
        // #253 BLOCKING #1 (2026-09-04 ruling): `createAll`'s postcondition on success is that
        // "artifacts" is ALWAYS present in `baseStorageSetups` -- either the operator's
        // `[storage.artifacts]` config or the synthesized default, and either one failing now
        // aborts boot at the `baseStorageSetupsResult` guard above, before this line is ever
        // reached. The old `.or(StorageFactory.defaultArtifactStorage(...))` fallback substituted a
        // second, independently-built, always-unencrypted instance whenever "artifacts" happened to
        // be missing from the map -- which is exactly how a `wrapLocalDisk` refusal over
        // pre-existing plaintext used to boot successfully anyway (BLOCKING #1's root cause).
        // `defaultArtifactStorage` is retired as dead code; there is no longer a legitimate reason
        // for "artifacts" to be absent here.
        //
        // #253 review round 2 NOTE: `Objects.requireNonNull` turns a violated invariant into a named
        // failure pointing at THIS comment's reasoning, instead of a bare NPE at `.instance()` that
        // gives a future reader no hint the absence was ever supposed to be impossible.
        var artifactStorage = Objects.requireNonNull(storageSetups.get("artifacts"),
                                                     "storageSetups missing \"artifacts\" after createAll succeeded -- invariant violated")
                                     .instance();
        // #783: same invariant as "artifacts" above -- `createAll` synthesizes a "content" entry
        // whenever `[storage.content]` wasn't explicit, so its absence here would mean
        // `baseStorageSetupsResult` should already have failed at the guard above. Replaces the old
        // keyring-less `StorageFactory.defaultContentStorage(dhtClientOption)` call at the
        // `registerRuntimeExtensions` site below, which built ContentStore's StorageInstance entirely
        // outside `storageSetups` -- no MetadataStore, no demotion/GC, never encrypted (#250/#803,
        // #253's WARN at that site is retired along with it).
        var contentStorage = Objects.requireNonNull(storageSetups.get("content"),
                                                    "storageSetups missing \"content\" after createAll succeeded -- invariant violated")
                                    .instance();
        // #1778: the versions of each artifact are kept in consensus, where concurrent publishes are folded by the
        // applier; the bytes and per-version metadata stay in the DHT.
        var artifactStore = RepositoryFactory.artifactStore(dhtClient,
                                                            artifactStorage,
                                                            config.sliceConfig(),
                                                            clusterNode,
                                                            kvStore);
        var repositoryFactory = RepositoryFactory.repositoryFactory(artifactStore);
        var repositories = repositoryFactory.createAll(config.sliceConfig());

        return new ArtifactStorageOutputs(streamStorageSetup, contentStorage, artifactStore, repositories);
    }

    /// What `storageMaintenance` reads from the assembly built so far.
    record StorageMaintenanceInputs(AetherNodeConfig config,
                                    Map<String, StorageFactory.StorageSetup> storageSetups,
                                    OperatorWarningSink operatorWarningSink) {}

    /// What `storageMaintenance` hands back for the rest of the assembly.
    record StorageMaintenanceOutputs(DelegatedStorageAdapter delegatedStorageAdapter,
                                     StorageMaintenanceDriver storageMaintenanceDriver) {}

    /// The composite demotion, garbage-collection and promotion managers and the maintenance driver that cadences them.
    static StorageMaintenanceOutputs storageMaintenance(StorageMaintenanceInputs in) {
        var config = in.config();
        var storageSetups = in.storageSetups();
        var operatorWarningSink = in.operatorWarningSink();
        // #231 Step 1: ClusterSyncScheduler (metricsScheduler) is quorum-driven via its
        // onQuorumStateChange route below; task-assignment registration was redundant AND harmful
        // (deactivate() drove the FSM to Dormant on METRICS-group reassignment even while quorum
        // held, never resuming — the leader-change detection-blindness bug). DeploymentMetricsScheduler
        // is leader-pinned via toggleDeploymentMetricsOnLeaderChange instead of task-assignment.
        // #231 Step 2: the remaining control-plane components (CDM, LoadBalancer, ControlLoop,
        // TTMManager, RollbackManager, AbTestManager, DeploymentManager, StorageAdapter, StreamingCoordinator)
        // are leader-pinned via their toggle*OnLeaderChange routes in collectRouteEntries / allEntries below.
        // #250: real demotion + GC, fanned out across every storage setup this node holds via
        // StorageFactory.composite* -- `artifacts`, `content` and `streams` (`storageSetups`, built
        // above). #783: `content` used to be built by a separate keyring-less
        // `defaultContentStorage` call entirely outside `storageSetups`, so it never reached
        // compositeDemotionManager/compositeGarbageCollector and got no demotion or GC from this
        // driver; it is now synthesized through the same `createAll` path as `artifacts` (see the
        // `contentStorage` invariant comment above) and is covered like every other entry. Leader
        // activation below (toggleStorageOnLeaderChange) no longer activates any garbage collector (the
        // composite's activate is a no-op: GC is per node, see StorageFactory#compositeGarbageCollector). Both
        // DefaultDemotionManager and DefaultStorageGarbageCollector self-gate on their internal
        // `active` flag, so the periodic driver below can call demote()/collectGarbage()
        // unconditionally on every node (leader or not) and it safely no-ops until that node's
        // collector is activated.
        //
        // Promotion, demotion and garbage collection of `artifacts` and `content` are NOT leader-pinned: their
        // tiers are private to each node, so a leader-only manager would warm, shrink and reclaim only the
        // leader's disk. All are activated on every node when it reports ready (signalReadyActivatingStorage, below) and deactivated
        // on stop. `streams` stays as it was: leader-pinned demotion, no promotion.
        var compositeDemotionManager = StorageFactory.compositeDemotionManager(storageSetups);
        var compositeGarbageCollector = StorageFactory.compositeGarbageCollector(storageSetups);
        var compositePromotionManager = StorageFactory.compositePromotionManager(storageSetups);
        var leaderMaintenance = DegradeReporter.degradeReporter(DelegatedStorageAdapter.REPORT_SUBJECT);

        leaderMaintenance.bind(operatorWarningSink);
        var delegatedStorageAdapter = DelegatedStorageAdapter.delegatedStorageAdapter(compositeDemotionManager,
                                                                                      compositeGarbageCollector,
                                                                                      leaderMaintenance);
        var storageMaintenanceDriver = StorageMaintenanceDriver.storageMaintenanceDriver(compositeDemotionManager,
                                                                                         compositePromotionManager,
                                                                                         PromotionStallWatch.promotionStallWatch(storageSetups,
                                                                                                                                 operatorWarningSink),
                                                                                         compositeGarbageCollector);

        LOG.info("Storage maintenance enabled: demotion+promotion+GC cadence={}, storage setups={}",
                 config.timeouts().storageMaintenance().interval(),
                 storageSetups.keySet());

        return new StorageMaintenanceOutputs(delegatedStorageAdapter, storageMaintenanceDriver);
    }

    /// What `storageTicks` reads from the assembly built so far.
    record StorageTicksInputs(AetherNodeConfig config,
                              Map<String, StorageFactory.StorageSetup> storageSetups,
                              StorageMaintenanceDriver storageMaintenanceDriver,
                              Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The metadata snapshot tick and the storage maintenance tick, deferred until the node starts.
    static Unit storageTicks(StorageTicksInputs in) {
        var config = in.config();
        var storageSetups = in.storageSetups();
        var storageMaintenanceDriver = in.storageMaintenanceDriver();
        var defer = in.defer();
        // A3b snapshot driver: `SnapshotManager.maybeSnapshot()` has no other caller — without this tick
        // no metadata snapshot is ever taken and nothing survives a restart. Iterate ALL storage setups
        // (streams + artifacts/content), so every MetadataStore's refs are persisted; maybeSnapshot
        // self-gates on its mutation-count / interval trigger, so a 10s tick is cheap when not due.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> snapshotAllSetups(storageSetups),
                                                               METADATA_SNAPSHOT_INTERVAL));
        // #250 storage maintenance driver: demote()/collectGarbage() have no other caller — without this
        // tick, tiered storage never shrinks in production regardless of the leader-pinned adapter's
        // activation state. Cadence is operator-configurable ([timeouts.storage_maintenance] interval,
        // default 5m); both operations self-gate internally, so this tick is cheap when inactive or idle.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(storageMaintenanceDriver::tick,
                                                               config.timeouts().storageMaintenance().interval()));

        return Unit.unit();
    }

    /// The per-node stream WAL directory when it is writable.
    ///
    /// An UNWRITABLE dir is a BOOT ERROR by default (#634 item 2). The old behaviour — one startup WARN,
    /// then every publish acks with no fsync — silently converted "durable entity" into "in-memory
    /// entity": the ack path (`awaitDurable`) passes an already-resolved barrier when the WAL is absent, and
    /// nothing downstream can tell. A node that cannot honour the durability its streams declare must
    /// say so at the moment an operator is looking at it, not in a log line nobody reads back.
    ///
    /// Explicitly best-effort deployments (Forge on a read-only mount, dev profiles) opt in with the
    /// `aether.allowNonDurableStreams=true` system property or `AETHER_ALLOW_NON_DURABLE_STREAMS=true`
    /// env — and keep exactly the previous degrade-with-WARN.
    private static Option<Path> resolveStreamWalDir(AetherNodeConfig config) {
        var walDir = streamWalBaseDir(config);

        return createStreamDirectory(walDir).fold(cause -> walDisabled(walDir, cause), _ -> Option.some(walDir));
    }

    private static void snapshotAllSetups(Map<String, StorageFactory.StorageSetup> storageSetups) {
        storageSetups.values().forEach(setup -> setup.snapshotManager()
                                                     .maybeSnapshot());
    }

    record unused() implements StorageAssembly {}
}
