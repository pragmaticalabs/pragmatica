// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.routes.RetentionRoutes;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.ReadLinearizationMode;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNode.ResourceProviderSetup;
import org.pragmatica.aether.node.AetherNode.StreamOwnershipRetry;
import org.pragmatica.aether.node.AetherNode.StreamOwnershipViews;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.ClusterEventsLimits;
import org.pragmatica.aether.node.EntityOwnershipReconciler;
import org.pragmatica.aether.node.RouteCollisionAnnouncer;
import org.pragmatica.aether.node.ScheduledTaskOutcomeAnnouncer;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.StreamFailoverAnnouncer;
import org.pragmatica.aether.node.StreamIsrAnnouncer;
import org.pragmatica.aether.node.StreamLineageAnnouncer;
import org.pragmatica.aether.node.StreamMemoryBudget;
import org.pragmatica.aether.node.projection.CommitWitness;
import org.pragmatica.aether.node.projection.PartitionBounds;
import org.pragmatica.aether.node.projection.ProjectionAwareCursorStore;
import org.pragmatica.aether.node.projection.ProjectionNodeSupport;
import org.pragmatica.aether.node.projection.ProjectionRegistry;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter;
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry;
import org.pragmatica.aether.node.stream.TopicGroupDeclarationSource;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.storage.DelegatedStorageAdapter;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.DurableSealedOffsetSource;
import org.pragmatica.aether.stream.KvCommittedStreamOwnerSource;
import org.pragmatica.aether.stream.KvStreamOwnerEpochSource;
import org.pragmatica.aether.stream.LinearizableBarrier;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPublisherFactory;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.StreamingCoordinator;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.replication.AlignedRecovery;
import org.pragmatica.aether.stream.replication.ForwardCatchupTransport;
import org.pragmatica.aether.stream.replication.GovernorFailoverHandler;
import org.pragmatica.aether.stream.replication.IsrMonitor;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.aether.stream.replication.SelfWatermark;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.RefDurability;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SealerThresholds;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.SharedSegmentCollection;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.StreamFootprint;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.aether.stream.topic.DlqEnvelope;
import org.pragmatica.aether.stream.topic.DlqStreamSink;
import org.pragmatica.aether.stream.topic.RoutingDeadLetterSink;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.SharedDelete;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import static org.pragmatica.aether.node.AetherNode.DEFAULT_STREAM_RETENTION_MS;
import static org.pragmatica.aether.node.AetherNode.ISR_MONITOR_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.RETENTION_INVARIANT_CHECK_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.RING_AGE_TICK_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.STREAM_BACKFILL_REDRIVE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.STREAM_CATCHUP_BATCH_SIZE;
import static org.pragmatica.aether.node.AetherNode.STREAM_CONSUMER_RECONCILE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.STREAM_RESHUFFLE_RECONCILE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.WAL_TRUNCATE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.reconcileReplicaSet;
import static org.pragmatica.aether.node.AetherNode.streamCatchupView;
import static org.pragmatica.aether.node.AetherNode.streamIsrInputs;
import static org.pragmatica.aether.node.AetherNode.wireMembershipDecisionTail;


/// The stream unit of the node assembly: the replication manager and transport, the stream partition manager with its
/// segment index, storage and cursor stores, the in-sync-replica and owner activation machinery, the backfill, the
/// consumers, projection and streaming coordinator, the write, read and forward routers and their extensions, and the
/// periodic ticks that drive them. Moved out of `AetherNode` without change; the root calls the phases in the order it
/// always ran them.
public sealed interface StreamAssembly {
    /// What `streamReplication` reads from the assembly built so far.
    record StreamReplicationInputs(AetherNodeConfig config,
                                   MessageRouter.DelegateRouter delegateRouter,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                   SliceCodec nodeCodec,
                                   OwnershipEpochHighWater ownershipEpochHighWater,
                                   ClusterEventsLimits clusterEventsLimits,
                                   StreamMemoryBudget streamMemoryBudget,
                                   StorageFactory.StorageSetup streamStorageSetup,
                                   AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                                   String clusterEventsStreamName,
                                   ClusterEventAggregator eventAggregator,
                                   OperatorWarningSink operatorWarningSink,
                                   ArrayList<MessageRouter.Entry<?>> allEntries) {}

    /// What `streamReplication` hands back for the rest of the assembly.
    record StreamReplicationOutputs(ReplicaRegistry streamReplicaRegistry,
                                    ReplicationTransport streamReplicationTransport,
                                    ReplicationManager streamReplicationManager,
                                    SegmentIndex streamSegmentIndex,
                                    StorageInstance streamStorage,
                                    CursorStore streamCursorStore,
                                    TieredStreamReader streamTieredReader,
                                    SegmentSealer streamSegmentSealer,
                                    StreamPartitionManager streamPartitionManager,
                                    StreamIsrAnnouncer streamIsrAnnouncer,
                                    StreamConfig clusterEventsStreamConfig,
                                    StreamNamespacesService streamNamespacesService,
                                    WatermarkTracker streamWatermarkTracker,
                                    SegmentReader streamSegmentReader,
                                    RetentionEnforcer streamRetentionEnforcer,
                                    AlignedRecovery streamAlignedRecovery,
                                    GovernorFailoverHandler streamFailoverHandler,
                                    StreamForwardTransport streamForwardTransport,
                                    StreamingConfig streamingConfig,
                                    StreamReadForwardMetrics streamReadForwardMetrics,
                                    StreamForwardClient streamForwardClient) {}

    /// The replica registry, the replication manager and transport, the partition manager with its segment index and stores, the ISR announcer and the forward client.
    /// The run-forming sealer's thresholds, as the node's `[streaming] seal_*` keys set them (#2201 C-02).
    static SealerThresholds sealerThresholds(StreamingConfig streaming) {
        return SealerThresholds.sealerThresholds(streaming.sealRunBytes(),
                                                 streaming.sealRunEvents(),
                                                 streaming.sealRunAge(),
                                                 streaming.sealMergeBytes());
    }

    static StreamReplicationOutputs streamReplication(StreamReplicationInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var nodeCodec = in.nodeCodec();
        var ownershipEpochHighWater = in.ownershipEpochHighWater();
        var clusterEventsLimits = in.clusterEventsLimits();
        var streamStorageSetup = in.streamStorageSetup();
        var clusterEventsControllerRef = in.clusterEventsControllerRef();
        var clusterEventsStreamName = in.clusterEventsStreamName();
        var eventAggregator = in.eventAggregator();
        var operatorWarningSink = in.operatorWarningSink();
        var allEntries = in.allEntries();
        var streamMaxMemoryBytes = in.streamMemoryBudget().maxMemoryBytes();
        // A6: activate replication. The replica registry (populated by the A2 ReplicaSetController from
        // HRW placement) and a network-backed ReplicationTransport are constructed here — ahead of the
        // StreamPartitionManager — so SPM can be given a REAL DefaultReplicationManager instead of
        // ReplicationManager.NONE. On every publishLocal the owner now replicates the event to its
        // registered replica set; the receive/apply side (streamReplicationReceiveHandler, wired below)
        // lands replicated events offset-preserving WITHOUT re-replicating (appendRecovered).
        var streamReplicaRegistry = ReplicaRegistry.replicaRegistry(config.streaming().caughtUpMaxLagOffsets());
        var replicationNetwork = clusterNode.network();
        org.pragmatica.aether.stream.replication.ReplicationTransport streamReplicationTransport = new org.pragmatica.aether.stream.replication.ReplicationTransport() {
            @Contract
            @Override
            public void send(NodeId target, org.pragmatica.aether.stream.replication.ReplicationMessage message) {
                replicationNetwork.send(target, message);
            }

            @Contract
            @Override
            public void send(NodeId target,
                             org.pragmatica.aether.stream.replication.ReplicationMessage message,
                             TimeSpan callerWait) {
                replicationNetwork.send(target, message, callerWait);
            }
        };
        // #261: the live-ack path promotes a replica to CAUGHT_UP only when its confirmed offset
        // reaches back to the owner's earliest retained offset. The partition manager is constructed
        // just below (it needs this manager), so the earliest-retained seam reads through a holder set
        // immediately after.
        var streamPartitionManagerRef = new AtomicReference<StreamPartitionManager>();
        var streamReplicationManager = ReplicationManager.replicationManager(config.self(),
                                                                             streamReplicaRegistry,
                                                                             streamReplicationTransport,
                                                                             (streamName, partition) -> Option.option(streamPartitionManagerRef.get())
                                                                                                              .map(spm -> spm.earliestRetainedOffset(streamName,
                                                                                                                                                     partition))
                                                                                                              .or(-1L));
        var streamSegmentIndex = new SegmentIndex();
        // A3b restore-at-boot: the streams MetadataStore was already restored from its latest disk
        // snapshot when `streamStorageSetup` was assembled (StorageFactory.restoreAndSignalReady), so
        // the durable `streams/` refs are present here. Rebuild the in-memory offset→segment index from
        // them so sealed segments are readable immediately after a same-node restart, before any new
        // append re-populates the index.
        streamSegmentIndex.rebuildFromRefs(streamStorageSetup.metadataStore());
        var streamStorage = streamStorageSetup.instance();
        // #2258 (spec 3.2 item 6): the refs a crash left at or below a durable reclaim floor are released now (rebuild did not
        // install them); StorageFactory gave the instance its identification predicate.
        streamSegmentIndex.drainReclaimedBelowFloor()
                          .forEach(refName -> streamStorage.deleteRef(refName, SharedDelete.IF_IDENTIFIED));
        // A4/A5: per-node durable cursor store + tiered reader over the same disk-backed stream storage;
        // registered as SPI extensions below so StreamAccessFactory persists consumer offsets and serves
        // post-restart reads from sealed cold segments.
        var streamCursorStore = CursorStore.cursorStore(streamStorage);
        var streamTieredReader = TieredStreamReader.tieredStreamReader(streamSegmentIndex, streamStorage);
        // #336 epoch-adoption: the committed owner-epoch source is SHARED between the live-append stamp
        // (StreamPartitionManager, below) and the backfill/recovery seam (streamAlignedRecovery, below), so
        // a recovered/backfilled event carries the SAME committed fencing token a live append would. Both
        // read the identical committed StreamPartitionOwnershipValue.ownerEpoch the fence high-water derives
        // from — otherwise the recovery seam's Epoch.ZERO (0:0) is rejected by an advanced high-water (1:N).
        var streamOwnerEpochSource = KvStreamOwnerEpochSource.kvStreamOwnerEpochSource(kvStore);
        // #1730 phase 2 (B11): the owner counts a replica's confirmation only under the committed epoch now in force.
        streamReplicationManager.ownerEpochs(streamOwnerEpochSource);
        // #1234: the sealer retains each evicted segment until storage has it; those copies are capped (#2201 C-03: at
        // `[streaming] seal_pending_cap_bytes`, by default the smaller of the stream memory budget and 256 MiB), and only past
        // that cap are appends refused (SEALING_BEHIND).
        // #1345: WAL truncation is bounded by the refs in the latest metadata snapshot ON DISK — the watermark
        // the `rebuildFromRefs` above would compute at the next boot — never by the live index, which runs
        // ahead of disk by every seal since that snapshot (STREAM_SNAPSHOT_* in StorageFactory bound the lag).
        // #1240: the entity log substrate asks the same sealer which evicted offsets are still in flight.
        // #1441: a partition WITHOUT a WAL has nothing above that snapshot but the refs, so its seals force a
        // snapshot before they resolve; boot's rebuilt floor then covers every completed seal on both paths.
        // #2201 C-02: evictions are sealed in runs, by the `[streaming] seal_*` thresholds.
        var streamSegmentSealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(streamStorage,
                                                                                                    streamSegmentIndex,
                                                                                                    RefDurability.snapshotted(streamStorageSetup.snapshotManager())),
                                                              in.streamMemoryBudget().sealPendingCapBytes(),
                                                              sealerThresholds(config.streaming()));
        var streamPartitionManager = StreamPartitionManager.streamPartitionManager(streamMaxMemoryBytes,
                                                                                   streamSegmentSealer,
                                                                                   streamReplicationManager,
                                                                                   clusterNode,
                                                                                   ownershipEpochHighWater,
                                                                                   streamOwnerEpochSource,
                                                                                   streamLogs(streamStorage),
                                                                                   streamSegmentIndex::lastSealedOffset,
                                                                                   DurableSealedOffsetSource.fromLatestSnapshot(streamStorageSetup.snapshotManager(),
                                                                                                                                streamSegmentIndex::adoptions));

        streamPartitionManagerRef.set(streamPartitionManager);
        // #1278 review: each stream's durable refs (sealed segments, reclaimed-through floors) and WAL are keyed by its
        // incarnation; the manager adopts the committed life before a stream materializes, so another life of the
        // name is never read, and reclaims the other lives' refs best-effort.
        streamPartitionManager.streamFootprint(StreamFootprint.streamFootprint(streamStorage,
                                                                               streamStorageSetup.metadataStore(),
                                                                               streamSegmentIndex));
        // #1596: provenance failures this node detects (an N13 mismatch at catch-up, a log holding records with no
        // owner-epoch history) are raised on the durable, backed-up partition flag -- the same flag the promotion
        // gate and cold-restart detection raise and read.
        streamPartitionManager.partitionFlags(PartitionFlags.kvPartitionFlags(clusterNode, kvStore, nodeCodec));
        streamPartitionManager.operatorWarnings(operatorWarningSink);
        // Know 73fd8d9759: a graceful stop that cannot seal what the sealer holds within its bound is reported to this node's sink.
        streamSegmentSealer.operatorWarnings(operatorWarningSink,
                                             config.self().id());
        // #1730 phase 2: a durable cut whose repair is not settled within twelve backfill redrive ticks is reported anyway.
        streamPartitionManager.repairReportBound(TimeSpan.timeSpan(STREAM_BACKFILL_REDRIVE_INTERVAL.millis() * 12L).millis());
        // `[streaming] reshuffle_concurrency` — set BEFORE any materialization, since it replaces the permit
        // pool wholesale. Until 2026-08-16 this bound was a compile-time constant while the paced-materialize
        // error message named it as a config key, so an operator whose backfills were starving had nothing to
        // turn. ConfigValidator rejects < 1 up front.
        streamPartitionManager.reshuffleConcurrency(config.streaming().reshuffleConcurrency());
        // stream-offheap-budget-spec §4.5c / reconciliation #14: route off-heap budget exhaustion
        // (create-floor + growth) OUT of aether-stream via the injected Consumer<Exhaustion> sink and
        // INTO the cluster-event aggregator, which stamps this node's id and emits a
        // StreamMemoryExceeded event through its un-gated emitLocal path (per-node fact, like
        // SelfDrainInitiated). The aggregator owns the per-(stream,phase) 60s throttle.
        streamPartitionManager.exhaustionSink(eventAggregator::onStreamMemoryExceeded);
        // #336: the two StreamConfigKey put-handlers run in registration order — hydrate the committed
        // config into replicaCatalog() FIRST (onStreamConfigPut), then place its replica set
        // (reconcileReplicaSetOnConfigPut). clusterEventsControllerRef is the late-bound holder for the
        // stream ReplicaSetController (set below, after the controller is built).
        // #1883: the in-sync set falling below / returning to the confirmation factor, derived from the committed ownership
        // Put AND from a committed config Put that moves the factor. The config handler runs FIRST: it reads the factor
        // the manager enforces before the Put and the one it will enforce after it, which the manager's own handler
        // (next) then installs.
        var streamIsrAnnouncer = StreamIsrAnnouncer.streamIsrAnnouncer(streamPartitionManager::confirmationFactorFor,
                                                                       streamPartitionManager::confirmationFactorAfter,
                                                                       streamPartitionManager::enforcedConfig,
                                                                       (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                       partition),
                                                                                                               StreamPartitionOwnershipValue.class),
                                                                       delegateRouter::route);
        var streamConfigKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              streamIsrAnnouncer::onConfigPut)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              streamPartitionManager::onStreamConfigPut)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              _ -> reconcileReplicaSetOnConfigPut(clusterEventsControllerRef))
                                                       .onRemove(AetherKey.StreamConfigKey.class,
                                                                 streamPartitionManager::onStreamConfigRemove)
                                                       .build();

        allEntries.addAll(streamConfigKvRouter.asRouteEntries());
        // B5b — provision system:cluster-events:1.0.0 as a REAL partition-managed stream.
        //
        // The events stream is now created in the StreamPartitionManager (single partition) via
        // SystemStreamFactories, exactly like app/system streams: createStream publishes the
        // StreamConfigKey into cluster KV, so the stream appears in replicaCatalog() → the
        // ReplicaSetController computes its HRW placement → the replica registry is populated →
        // replication (RF = systemReplicationFactor(N)) and PartitionedStreamAccess read-forwarding
        // both go live. The in-heap ClusterEventStreamBuffer/ClusterEventStreamWiring are gone.
        //
        // Serializer = the node SliceCodec (Serializer+Deserializer), which includes the generated
        // ApiCodecsNode.CODECS (B5a) → ClusterEvent encodes/decodes over the byte[] transport. This is
        // the same codec app streams are wired with.
        //
        // Retention is production-grade and config-overridable (item 4 / OOM guard): bounded on count,
        // bytes (off-heap hard cap), and age, mode ANY — see ClusterEventsLimits, checked at boot (#1549). The publisher/consumer refs the aggregator was
        // constructed with are bound here; until then emits fall back to log-only (bootstrap window).
        // #1564 (N2): the local partition the publisher/consumer wiring below creates at construction carries the
        // SAME factors the SystemStreamRegistrar commits — the committed `[replication.cluster_events]` as this node
        // sees it now, never a separate hardcoded CF (ClusterEventsLimits.streamConfig).
        var clusterEventsStreamConfig = clusterEventsLimits.streamConfig(clusterEventsStreamName,
                                                                         kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                          AetherValue.ClusterConfigValue.class));
        // Fix #3 / #1230: the forward-capable cluster-events CONSUMER and PUBLISHER are wired further below,
        // after streamForwardClient + streamReadForwardMetrics are constructed, so a non-replica node
        // read-forwards observability reads to a caught-up replica instead of reading its own empty local
        // partition, and a non-owner emit write-forwards to the owner instead of appending to its own replica
        // ring. The aggregator reads both lazily via clusterEventsConsumerRef::get /
        // clusterEventsPublisherRef::get, so deferring the wiring does not affect construction order.
        // Stage 5: stream-namespaces registry + service.
        //
        // Stage 4 deferred the StreamRegistry registration to here, where the namespace-listing
        // HTTP surface (StreamApiRoutes / StreamNamespacesRoutes) consumes it. The registry is
        // backed by the cluster KV-store (KvBackedStreamRegistry) so it shares the same state the
        // Stage-3 refcount-on-ACTIVE path writes to — one source of truth for stream lifecycle.
        // bootstrap() idempotently registers the system:* streams; failure is tolerated (log-only)
        // so a node still boots when consensus isn't yet quorate at this point.
        var streamRegistry = org.pragmatica.aether.slice.stream.KvBackedStreamRegistry.kvBackedStreamRegistry(clusterNode,
                                                                                                              kvStore);
        var streamNamespacesService = new StreamNamespacesService(streamRegistry,
                                                                  new org.pragmatica.aether.slice.stream.SystemStreamBootstrap(streamRegistry));
        // System-stream registration (system:cluster-events etc.) is driven LEVEL-TRIGGERED by the
        // self-healing SystemStreamRegistrar (Fix #1), not here. This point runs on [main] during
        // construction, seconds before consensus activates, so an eager bootstrap() always failed
        // "Node is inactive". The registrar (constructed + LeaderChange-wired below) retries on bounded
        // backoff until both legs commit, so a commit timeout in the post-leader-gain window no longer
        // leaves the stream unregistered (which made /api/events|alerts|traces return 200 []).
        // deleted alongside the audit publisher and RecentCommandsBuffer.
        var streamWatermarkTracker = WatermarkTracker.watermarkTracker();
        var streamSegmentReader = SegmentReader.segmentReader(streamStorage, streamSegmentIndex);
        // #1604: the durable segment tier's pressure -- retention warns at 85%, owner publishes are refused at 95%.
        var streamSegmentTierPressure = SegmentTierPressure.localDiskOf(streamStorage);

        streamPartitionManager.segmentTierPressure(streamSegmentTierPressure);
        // #2201 Q6 = B: each stream's own committed retention policy rules its sealed tier; a stream this node has not
        // applied a committed config for is withheld, and one that declares no dimension keeps the node default age.
        var streamRetentionEnforcer = RetentionEnforcer.retentionEnforcer(streamStorage,
                                                                          streamSegmentIndex,
                                                                          streamPartitionManager::committedRetention,
                                                                          DEFAULT_STREAM_RETENTION_MS,
                                                                          (stream, partition) -> entityRetentionFloor(kvStore,
                                                                                                                      stream,
                                                                                                                      partition),
                                                                          streamStorageSetup.snapshotManager(),
                                                                          streamStorageSetup.garbageCollector(),
                                                                          streamSegmentReader,
                                                                          streamSegmentTierPressure);
        // #2201 3.2 item 6: every node runs its own retention over its own segments, from node start to node stop
        // (AetherNode's stop path closes it); it is no longer pinned to the leader by StreamingCoordinator.
        // #2258: the released segments' blocks are deleted from every tier by their own bounded run, started and closed
        // with the enforcer.
        streamRetentionEnforcer.sharedCollection(SharedSegmentCollection.sharedSegmentCollection(streamStorageSetup.garbageCollector(),
                                                                                                 streamStorageSetup.snapshotManager(),
                                                                                                 StorageGarbageCollector.SharedDeleteLimits.DEFAULT));
        streamRetentionEnforcer.operatorWarnings(operatorWarningSink,
                                                 config.self().id());
        streamRetentionEnforcer.start();
        // A6: streamReplicaRegistry is created earlier (above StreamPartitionManager) so it can be
        // shared with the now-active DefaultReplicationManager. The same registry instance is the one
        // the A2 ReplicaSetController populates from HRW placement.
        // A4 / #1505: every replica-side recovery apply — governor-failover segment replay and the A4 backfill
        // below — lands each event at its OWN owner offset WITHOUT re-replicating (the receiver is not an owner),
        // through the same ordered section as the live receive handler (whose `streamPartitionManager::appendRecovered`
        // binds the offset-addressed overload). A live batch or a lagging replay floor therefore cannot shift an
        // event: an offset already held is verified, never re-appended at the tail (#1505 F1).
        // #336 epoch adoption (NOT invention): stamp the recovery/backfill append with the SAME committed
        // owner epoch a live publish stamps (streamOwnerEpochSource reads the committed
        // StreamPartitionOwnershipValue.ownerEpoch — the identical source the fence high-water is seeded
        // from), so a backfilled event is equal-or-newer than the partition high-water and passes the fence
        // instead of being rejected at the Epoch.ZERO floor (0:0 < 1:N). Cold-start/unowned arcs read
        // Epoch.ZERO == the fence's ZERO default (equal → passes), so fresh-stream recovery is not regressed.
        // The fence itself is untouched — this is a truthful stamp, not a wholesale exemption.
        // #1596: that stamp FENCES the append and never attributes it — a caught-up record may predate the
        // current epoch. Its provenance is the source's owner-epoch slice, which the same seam checks (N13)
        // and records before the page is applied (StreamPartitionManager.alignedRecovery, the fence epoch read
        // from this same streamOwnerEpochSource).
        AlignedRecovery streamAlignedRecovery = streamPartitionManager.alignedRecovery();
        var streamFailoverHandler = GovernorFailoverHandler.governorFailoverHandler(streamReplicaRegistry,
                                                                                    streamAlignedRecovery,
                                                                                    streamPartitionManager::syncReplicated);
        // A4: production catch-up wiring. The forward transport/client are constructed here (ahead of
        // the A6 read-forwarding wiring further below, which reuses the same instances) so the
        // ReplicaSetController can be given a real onBecameReplica backfill callback instead of the
        // A2 no-op seam.
        //   - catch-up transport: pages StreamForwardClient.readRemote until the source is drained.
        //   - PartitionBackfill: picks the best caught-up peer, applies events, flips self CAUGHT_UP.
        var streamForwardTransport = createStreamForwardTransport(clusterNode.network());
        var streamingConfig = config.streaming();
        var streamReadForwardMetrics = StreamReadForwardMetrics.inMemory();
        var streamForwardClient = StreamForwardClient.streamForwardClient(config.self(),
                                                                          streamForwardTransport,
                                                                          streamingConfig.publishForwardTimeout(),
                                                                          streamingConfig.readForwardTimeout(),
                                                                          streamReadForwardMetrics);

        return new StreamReplicationOutputs(streamReplicaRegistry,
                                            streamReplicationTransport,
                                            streamReplicationManager,
                                            streamSegmentIndex,
                                            streamStorage,
                                            streamCursorStore,
                                            streamTieredReader,
                                            streamSegmentSealer,
                                            streamPartitionManager,
                                            streamIsrAnnouncer,
                                            clusterEventsStreamConfig,
                                            streamNamespacesService,
                                            streamWatermarkTracker,
                                            streamSegmentReader,
                                            streamRetentionEnforcer,
                                            streamAlignedRecovery,
                                            streamFailoverHandler,
                                            streamForwardTransport,
                                            streamingConfig,
                                            streamReadForwardMetrics,
                                            streamForwardClient);
    }

    /// What `streamOwnership` reads from the assembly built so far.
    record StreamOwnershipInputs(AetherNodeConfig config,
                                 MessageRouter.DelegateRouter delegateRouter,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 AetherNode.EpochSources epochSources,
                                 HlcClock hlcClock,
                                 BooleanSupplier isLeaderSupplier,
                                 Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                                 ClusterTopologyManager clusterTopologyManager,
                                 AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                                 OperatorWarningSink operatorWarningSink,
                                 ArrayList<MessageRouter.Entry<?>> allEntries,
                                 MembershipFsm membershipFsm,
                                 ReplicaRegistry streamReplicaRegistry,
                                 ReplicationTransport streamReplicationTransport,
                                 ReplicationManager streamReplicationManager,
                                 SegmentIndex streamSegmentIndex,
                                 TieredStreamReader streamTieredReader,
                                 StreamPartitionManager streamPartitionManager,
                                 StreamIsrAnnouncer streamIsrAnnouncer,
                                 AlignedRecovery streamAlignedRecovery,
                                 StreamingConfig streamingConfig,
                                 StreamForwardClient streamForwardClient,
                                 Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `streamOwnership` hands back for the rest of the assembly.
    record StreamOwnershipOutputs(AetherNode.StreamOwnershipViews streamOwnershipViews,
                                  PartitionBackfill streamPartitionBackfill,
                                  ExecutorService streamBackfillExecutor,
                                  ReplicaSetController streamReplicaSetController,
                                  Runnable streamReplicationShutdown) {}

    /// The catch-up transport, ownership views, backfill, the replica set controller, owner activation, the ISR monitor and the replication shutdown hook.
    static StreamOwnershipOutputs streamOwnership(StreamOwnershipInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var epochSources = in.epochSources();
        var hlcClock = in.hlcClock();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterCommandApplier = in.clusterCommandApplier();
        var clusterTopologyManager = in.clusterTopologyManager();
        var clusterEventsControllerRef = in.clusterEventsControllerRef();
        var operatorWarningSink = in.operatorWarningSink();
        var allEntries = in.allEntries();
        var membershipFsm = in.membershipFsm();
        var streamReplicaRegistry = in.streamReplicaRegistry();
        var streamReplicationTransport = in.streamReplicationTransport();
        var streamReplicationManager = in.streamReplicationManager();
        var streamSegmentIndex = in.streamSegmentIndex();
        var streamTieredReader = in.streamTieredReader();
        var streamPartitionManager = in.streamPartitionManager();
        var streamIsrAnnouncer = in.streamIsrAnnouncer();
        var streamAlignedRecovery = in.streamAlignedRecovery();
        var streamingConfig = in.streamingConfig();
        var streamForwardClient = in.streamForwardClient();
        var defer = in.defer();
        var streamCatchupTransport = ForwardCatchupTransport.forwardCatchupTransport(streamForwardClient,
                                                                                     STREAM_CATCHUP_BATCH_SIZE,
                                                                                     operatorWarningSink,
                                                                                     System::currentTimeMillis);
        // Cold-start deadlock-break: a watermark+reachability probe over the same forward-read transport.
        // A REACHABLE peer answers with the highest local offset it holds (its watermark; -1 when empty), or,
        // for a partition it holds but has not materialized, its durable watermark; an UNREACHABLE peer's
        // read fails (timeout) and the probe propagates that failure, which the orchestrator treats as "do not
        // self-promote past a node that might hold newer state".
        ReplicaWatermarkProbe streamWatermarkProbe = (target, streamName, partition) -> probePeerWatermark(streamForwardClient,
                                                                                                           target,
                                                                                                           streamName,
                                                                                                           partition);
        // Self's local watermark: the local partition's HIGHEST held offset (ring head; -1 when the
        // partition is absent/empty). Read locally — this is the SAME notion the peer probe computes
        // remotely (its highest offset), so the highest-watermark contest is symmetric, AND it is the
        // base for the #333 owner-source backfill `fromOffset` (head + 1 = next-expected, contiguous).
        // NOTE: this is `headOffset` (highest), NOT `tailOffset` (earliest retained) — using the tail
        // understated self's position, breaking both the cold-start contest symmetry and the owner-source
        // contiguous fromOffset.
        SelfWatermark streamSelfWatermark = streamPartitionManager::appendedHead;
        // #491 F4: the committed StreamPartitionOwnershipValue.owner source, hoisted here so the backfill
        // orchestrator can gate HRW self-election on it (a diverged/empty-ring node must not self-promote
        // while a DIFFERENT node is the committed owner). Reused by the #265 owner-release guard below.
        //
        // #568: the committed record is only authoritative while its holder is ALIVE. Committed stream
        // ownership has no relinquish path other than the owner itself releasing it, so when the committed
        // owner dies the record outlives it forever. That wedged a partition permanently: a fresh
        // CTM replacement HRW-ranked itself owner of a stream with existing history, but `isSelfOwner`
        // reads COMMITTED ownership and answered false, so `promoteOwner` was never taken; HRW-owner==self
        // then filtered to none() and the flow fell to `backfillViaRegistryOrColdStart` ->
        // `handleNoSource` -> `waitThenPromote`, which suppressed self-promotion precisely BECAUSE a
        // committed owner existed (correctly preserving the #445 empty-owner distrust gate). Every step
        // correct; composed, no exit. Observed live: an owner stuck SYNCING@-1 for 13+ minutes, looping
        // every 20s, while four replicas sat CAUGHT_UP holding the data.
        //
        // Filtering the record by liveness closes the class rather than the instance — ANY partition whose
        // committed owner dies is otherwise exposed, not just system streams. `countedMembers()` is the
        // right set: ROLE-BLIND (a stream owner may be a worker) and MEMBER+SUSPECT, so a transient
        // SUSPECT still holds ownership and only a confirmed DEAD member releases it.
        //
        // The empty-set guard is load-bearing: before the FSM has any members (boot window) an unguarded
        // filter would reject EVERY committed owner and reintroduce the #491 F4 self-promote this gate
        // exists to prevent. Empty membership means "cannot judge liveness", not "nobody is alive".
        //
        // #1230 ruling 3: the write-authority half (admission + replication sender check) is the RAW record;
        // only the routing half (backfill self-election, release guard) is liveness-filtered. Both come from
        // StreamOwnershipViews so the split is decided — and pinned by StreamOwnershipViewsTest — in one place.
        var streamOwnershipViews = StreamOwnershipViews.streamOwnershipViews(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore),
                                                                             membershipFsm);
        var streamCommittedOwnerSource = streamOwnershipViews.routing();
        var placementMembers = livePlacementMembers(clusterTopologyManager.observer()::coreNodes, membershipFsm);
        var streamPartitionBackfill = PartitionBackfill.partitionBackfill(streamReplicaRegistry,
                                                                          streamAlignedRecovery,
                                                                          streamCatchupTransport,
                                                                          streamReplicationTransport,
                                                                          streamWatermarkProbe,
                                                                          streamSelfWatermark,
                                                                          config.self(),
                                                                          streamingConfig.backfillSourceWaitBound(),
                                                                          () -> streamPlacementMembers(clusterEventsControllerRef,
                                                                                                       placementMembers),
                                                                          streamCommittedOwnerSource,
                                                                          streamPartitionManager::syncReplicated,
                                                                          streamPartitionManager.quarantineView(),
                                                                          Option.some(streamingConfig.backfillFlightIdleBound()))
                                                       // #2077: the RAW committed ISR, never the liveness-filtered routing view above.
                                                       .withCommittedIsr(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore));
        var streamBackfillExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable,
                                                                                                "stream-partition-backfill"));
        // A2: per-node controller that reconciles the (previously never-populated) ReplicaRegistry
        // against the HRW-derived desired replica set on every membership change. Members are the single
        // live placement source (#1550, placementMembers); cluster size comes from the consensus topology
        // observer; the stream catalog (name/partitions/
        // confirmationFactor + partition-has-data) is adapted from the partition manager. The A4
        // catch-up seam now runs backfill off the reconcile thread on a dedicated executor.
        //
        // 1d-iii / #265: the leader-only StreamPartitionOwnershipWriter, fired by the controller's
        // onReconcilePassComplete batch driver seam. It mirrors the DHT BootstrapModule ownership writer:
        //   - isLeaderSupplier / rabiaTermSupplier / hlcClock are the same suppliers the DHT writer uses,
        //   - CommittedOwnership reads the committed StreamPartitionOwnershipValue exactly as
        //     KvStreamOwnerEpochSource does (getTyped on the StreamPartitionOwnershipKey),
        //   - HrwOwner late-binds to streamReplicaSetController::desiredOwner through clusterEventsControllerRef
        //     (set to the same controller below, before the first reconcile fires the driver) — #1555 sticky
        //     ownership: keep the committed owner while it is in the leader's live set, else HRW rank-0.
        // The writer self-limits: writeOwnershipChange returns none() on a follower (its isLeaderSupplier
        // gate short-circuits BEFORE any KV read) and none() for an unchanged owner, so the driver only
        // emits a consensus Put on the leader and only for genuinely-moved partitions. #265 increment 6:
        // the driver is now BATCH-shaped — the controller flushes ONE reconciled (stream, partition) list
        // per reconcile pass (onReconcilePassComplete) and driveStreamOwnership applies the emitted Puts as
        // a SINGLE consensus batch, so a mass reshuffle commits one batch per pass, not one apply per moved
        // partition. This keeps the reshuffle fan-out bound the promise #265 makes.
        var streamOwnershipWriter = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeaderSupplier,
                                                                                                  epochSources.generationEpoch(),
                                                                                                  hlcClock,
                                                                                                  (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                  partition),
                                                                                                                                          StreamPartitionOwnershipValue.class),
                                                                                                  (stream, partition) -> Option.option(clusterEventsControllerRef.get()).flatMap(ownershipController -> ownershipController.desiredOwner(stream,
                                                                                                                                                                                                                                         partition)),
                                                                                                  streamIsrInputs(clusterEventsControllerRef),
                                                                                                  () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                                         LeaderValue.class));
        // #1339 residual: a failed ownership write re-arms the reconcile through the same coalescing entry, backed off.
        var streamOwnershipRetry = StreamOwnershipRetry.streamOwnershipRetry(SharedScheduler::schedule,
                                                                             () -> reconcileReplicaSet(clusterEventsControllerRef));
        var streamReplicaSetController = ReplicaSetController.replicaSetController(streamReplicaRegistry,
                                                                                   config.self(),
                                                                                   placementMembers,
                                                                                   clusterTopologyManager.observer()::clusterSize,
                                                                                   streamPartitionManager.replicaCatalog(),
                                                                                   (streamName, partition) -> streamBackfillExecutor.execute(() -> materializeThenBackfill(streamPartitionManager,
                                                                                                                                                                           streamPartitionBackfill,
                                                                                                                                                                           streamName,
                                                                                                                                                                           partition)),
                                                                                   (List<PartitionKey> reconciled) -> driveStreamOwnership(streamOwnershipWriter,
                                                                                                                                           clusterCommandApplier,
                                                                                                                                           streamOwnershipRetry,
                                                                                                                                           reconciled));
        // B5b: bind the owner-gate ref so ClusterEventAggregator.emit can consult isOwner(...) for
        // (system:cluster-events:1.0.0, partition 0). isOwner is computed from the live HRW placement
        // against the current topology, independent of reconcile, so it is correct as soon as members
        // are visible (and true for a steady-state single-node cluster).
        clusterEventsControllerRef.set(streamReplicaSetController);
        // #1730 owner ruling: every node derives the failover refusal/resolution event from the committed ownership Put;
        // the cluster-events aggregator publishes only on its partition owner, so it reaches the stream exactly once.
        var streamFailoverAnnouncer = StreamFailoverAnnouncer.streamFailoverAnnouncer(() -> streamIsrInputs(clusterEventsControllerRef).liveMembers(),
                                                                                      delegateRouter::route);
        // #1873: and for an owner beginning a new epoch of its own (its ring was rebuilt).
        var streamLineageAnnouncer = StreamLineageAnnouncer.streamLineageAnnouncer(delegateRouter::route);

        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamFailoverAnnouncer::onOwnershipPut)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamIsrAnnouncer::onOwnershipPut)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamLineageAnnouncer::onOwnershipPut)
                                              .build()
                                              .asRouteEntries());
        // #1723: a scheduled task's unknown fire outcome, and its late resolution, derived by every node from the committed
        // task state; the aggregator publishes on the cluster-events owner only, throttled per task.
        var scheduledTaskOutcomeAnnouncer = ScheduledTaskOutcomeAnnouncer.scheduledTaskOutcomeAnnouncer(delegateRouter::route);
        // #1206: two artifacts serving one route, derived by every node from the committed route table.
        var routeCollisionAnnouncer = RouteCollisionAnnouncer.routeCollisionAnnouncer(delegateRouter::route);

        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.NodeRoutesKey.class, routeCollisionAnnouncer::onRoutesPut)
                                              .onRemove(AetherKey.NodeRoutesKey.class,
                                                        routeCollisionAnnouncer::onRoutesRemove)
                                              .build()
                                              .asRouteEntries());
        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.ScheduledTaskStateKey.class,
                                                     scheduledTaskOutcomeAnnouncer::onStatePut)
                                              .build()
                                              .asRouteEntries());
        // #1555 sticky ownership: every node routes by the COMMITTED ownership record (HRW only before a record
        // exists); the leader's writer alone judges liveness (ReplicaSetController#desiredOwner). Backfill sources
        // from, and self-elects against, the same owner.
        streamReplicaSetController.committedOwnerSource((stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                        partition),
                                                                                                StreamPartitionOwnershipValue.class)
                                                                                      .map(StreamPartitionOwnershipValue::owner));
        // #1730: the committed ISR keeps its members placed (so they keep receiving the partition), and a CF >= 2
        // confirmation needs every one of them. A record minted before #1730 (isrVersion 0) carries no committed ISR.
        streamReplicaSetController.committedIsrSource((stream, partition) -> committedIsr(kvStore, stream, partition).or(List.of()));
        streamPartitionBackfill.ownerResolver(streamReplicaSetController::ownerFor);
        // #265 increment 1/2: late-bind the placement-role supplier now that the controller exists. The
        // controller is constructed AFTER StreamPartitionManager (it consumes replicaCatalog()), so this
        // is the same construction-order inversion the streamPartitionManagerRef seam resolves above —
        // resolved here by a settable field rather than an AtomicReference. Increment 2 flipped the gate:
        // hydrate/create now materialize a partition ring IFF roleFor(stream, partition) ∈ {OWNER, REPLICA},
        // so a non-replica node holds the partition metadata-only (no ring, no off-heap bytes) — THE memory
        // win. The reconcile hook (materializeThenBackfill, bound above via onBecameReplica) and the
        // owner-append safety valve materialize a deferred ring once the role resolves. Forge/unit managers
        // keep the default always-OWNER supplier (materialize-everything, unchanged).
        streamPartitionManager.placementRoleSupplier(streamReplicaSetController::roleFor);
        // #265 increment 4: bind the aggregate partition-guard's cluster-size source to the SAME
        // topology-observer count the ReplicaSetController uses for HRW placement, so the create-time guard's
        // node count matches placement. The default `() -> 0` in Forge/unit/legacy managers disables the
        // aggregate guard (only the per-stream ceiling applies).
        streamPartitionManager.clusterSizeSupplier(clusterTopologyManager.observer()::clusterSize);
        // Write-forward race fix: bind the owner-side forwarded-publish config source to committed KV state
        // so a forwarded publish that races ahead of this owner's onStreamConfigPut can lazily materialize
        // from the committed StreamConfigKey (preserving RF); absent config yields the retryable
        // StreamConfigNotYetVisible the forwarder backs off on. Forge/unit managers keep the default (none).
        streamPartitionManager.committedConfigSource(name -> kvStore.getTyped(AetherKey.StreamConfigKey.streamConfigKey(name),
                                                                              AetherValue.StreamConfigValue.class)
                                                                    .map(AetherValue.StreamConfigValue::config));
        // #265 increment 5: reshuffle lifecycle. The release catch-up gate reads the live replica registry
        // (self-excluded CAUGHT_UP count + self caught-up flag); the owner rule reads the committed
        // StreamPartitionOwnershipValue (release only once ownership names a DIFFERENT node than self). Both
        // are bound through static helpers so the supplier lambdas stay single-expression. The committed
        // owner source is the one hoisted above for the #491 F4 backfill self-election gate.
        streamPartitionManager.replicaCatchupSource((stream, partition) -> streamCatchupView(streamReplicaRegistry,
                                                                                             config.self(),
                                                                                             stream,
                                                                                             partition));
        streamPartitionManager.ownerReleaseGuard((stream, partition) -> committedOwnerElsewhere(streamCommittedOwnerSource,
                                                                                                config.self(),
                                                                                                stream,
                                                                                                partition));
        // #1230: application appends are admitted only on the committed owner. Ring possession authorizes
        // reads and replication receipt, never a write: the epoch fence cannot tell a live replica from the
        // owner, because both stamp the same committed epoch. Reads the RAW committed record, deliberately NOT
        // the #568 liveness-filtered view: liveness is a local verdict, not a fence — filtering would read an
        // owner this node has marked DEPARTED (alive but partitioned away) or has not yet seen as "absent", and
        // admit a second writer while the first still writes. A SUSPECT owner is never dropped by the filter.
        // A dead owner's partition is unwedged only by the leader committing a new ownership record; until
        // then writes fail retryable NotOwnerAppend (CTO ruling on #1230). No record admits (the cold-start
        // window, where the fence is inert and HRW routing alone picks the writer).
        streamPartitionManager.ownerWriteAdmission(streamOwnershipViews.writeAdmission(config.self()));
        // #1555 owner promotion gate: this node acts as a partition's owner (appends, servedByOwner, owner reads)
        // only after a no-op consensus round has refreshed its committed ownership view, the record still names
        // it, and it has caught up to every live placement member's watermark. The round is ALWAYS ordered here,
        // independent of the read-linearization knob, because the gate's correctness depends on it. The probe
        // uses the catch-up read class, so a peer's own promotion state never blocks it.
        var ownerActivation = OwnerActivation.ownerActivation(config.self(),
                                                              (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                              partition),
                                                                                                      StreamPartitionOwnershipValue.class),
                                                              (stream, partition) -> streamReplicaSetController.roleFor(stream,
                                                                                                                        partition) == ReplicaSetController.Role.OWNER,
                                                              Option.some(LinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                                        streamingConfig.readForwardTimeout())),
                                                              placementMembers,
                                                              (target, stream, partition) -> probePeerAppendedWatermark(streamForwardClient,
                                                                                                                        target,
                                                                                                                        stream,
                                                                                                                        partition),
                                                              streamSelfWatermark,
                                                              streamPartitionBackfill::catchUpOwnerFrom,
                                                              clusterNode::isActive,
                                                              OwnerPeerReads.ownerRange(config.self(),
                                                                                        streamPartitionManager,
                                                                                        streamTieredReader,
                                                                                        streamForwardClient::readRemoteCatchup,
                                                                                        STREAM_CATCHUP_BATCH_SIZE),
                                                              ownerPromotionAlarm(operatorWarningSink),
                                                              ownerPromotionAlarmWindow(config.timeouts()
                                                                                              .swim()
                                                                                              .suspectTimeout()),
                                                              (stream, partition) -> streamPartitionManager.ringIncarnation(stream,
                                                                                                                            partition)
                                                                                                           .or(-1L),
                                                              streamLineageCommit(() -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                         LeaderValue.class),
                                                                                  clusterCommandApplier,
                                                                                  hlcClock));
        // #1873 (KIP-320): every consumer read is checked, on the node that serves it, against the partition's COMMITTED epoch
        // starts; this is where that node reads them.
        streamPartitionManager.ownershipRecords((stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                partition),
                                                                                        StreamPartitionOwnershipValue.class));
        // #2201 C-16: a consumer read below the ring tail is served from the sealed tier (StreamPartitionManager.readServingTiered,
        // the validated read of StreamReadRouter and of the owner's forward handler). Unbound, every consumer behind the ring
        // would be moved to the ring tail; EmberConsumerPathTripwireTest#tierFallbackSeam_isBoundOnEveryBootedNode pins it.
        streamPartitionManager.tieredReader(streamTieredReader);
        ownerActivation.promotionEscapeAfter(streamingConfig.promotionEscapeAfter());
        // #1937: the promoted owner's backfill refuses for a peer's oversized event too, and reports it the way the gate does.
        // #2080: its cold-start contest proceeds past unreachable co-replicas only for a node the COMMITTED in-sync set names, and
        // reports that escape the way the gate does.
        bindPromotionAlarm(streamPartitionBackfill, operatorWarningSink, kvStore, streamingConfig.promotionEscapeAfter());
        streamPartitionManager.ownerServeGate(ownerActivation::admit);
        // #1730 phase 2: the gate's relaxation for a divergent peer respects the candidate's durable sealed floor, and a peer it
        // leaves out loses the row this registry kept for it from an earlier tenure.
        ownerActivation.sealedFloor(streamSegmentIndex::lastSealedOffset);
        ownerActivation.peerRingTail((node, stream, partition) -> node.equals(config.self())
                                                                  ? Promise.success(streamPartitionManager.visibleBounds(stream,
                                                                                                                         partition)
                                                                                                          .filter(bounds -> bounds.earliestRetained() >= 0L)
                                                                                                          .map(VisibleBounds::earliestRetained))
                                                                  : streamForwardClient.ringTailRemote(node,
                                                                                                       stream,
                                                                                                       partition)
                                                                                       .recover(_ -> Option.<Long> none()));
        ownerActivation.peerRows((stream, partition, peer) -> streamReplicaRegistry.updateWatermark(stream,
                                                                                                    partition,
                                                                                                    peer,
                                                                                                    - 1L,
                                                                                                    ReplicationState.SYNCING));
        // #1730: a partition with no live in-sync replica has no owner to report a block, so the controller reports it.
        streamPartitionManager.ownerBlockSource((stream, partition) -> ownerActivation.blockOf(stream, partition)
                                                                                      .orElse(() -> streamReplicaSetController.noInSyncReplica(stream,
                                                                                                                                               partition)));
        // #1730: the owner keeps its partitions' ISR current — a member lagging longer than [streaming] isr_lag_max
        // leaves, a caught-up replica joins — through guarded consensus writes only (liveness; never safety).
        var isrMonitor = IsrMonitor.isrMonitor(config.self(),
                                               () -> ownedPartitions(streamPartitionManager,
                                                                     kvStore,
                                                                     ownerActivation,
                                                                     config.self()),
                                               streamReplicaRegistry,
                                               () -> kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class),
                                               clusterCommandApplier,
                                               streamingConfig.isrLagMax(),
                                               System::nanoTime);
        // #1730: a CF >= 2 confirmation needs every member of the committed ISR plus every member this owner has
        // proposed to add to it (the maximal ISR). A record minted before #1730 (isrVersion 0) carries no ISR.
        streamReplicationManager.inSyncReplicaSource((stream, partition) -> committedIsrRecord(kvStore,
                                                                                               stream,
                                                                                               partition).map(record -> isrMonitor.maximalIsr(stream,
                                                                                                                                              partition,
                                                                                                                                              record)));
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(isrMonitor::tick, ISR_MONITOR_INTERVAL));
        // #2087: the owner repeats its visible position, so one lost announcement delays a replica by one tick, never longer.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::repeatVisible,
                                                               ISR_MONITOR_INTERVAL));
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class, ownerActivation::onQuorumStateChange));
        // Reconcile on every membership decision (all variants via the tail helper) and on
        // ClusterStateNotification edges (PASSIVE suppresses; PASSIVE->ACTIVE re-reconciles).
        wireMembershipDecisionTail(allEntries, streamReplicaSetController::onMembershipDecision);
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                                 streamReplicaSetController::onQuorumStateChange));
        // #1339/#1732: the placement inputs the two triggers above do not announce — voter install, leadership,
        // committed ownership records. See wireReplicaSetInputTriggers.
        wireReplicaSetInputTriggers(allEntries, streamReplicaSetController::reconcile, clusterNode::onVoterConfiguration);
        // Initial reconcile once membership is available; serialized on the controller executor, so
        // this is a safe no-op until the topology observer reports core members.
        streamReplicaSetController.reconcile();
        // #932: one thread each per node, both daemon and neither closed on stop — nine of each survived a
        // nine-node stop. The controller's close() is shutdownNow on the executor it owns; the backfill
        // executor is this method's own. Controller first: its reconcile is what submits to the backfill.
        Runnable streamReplicationShutdown = () -> {
            streamReplicaSetController.close();
            streamBackfillExecutor.shutdownNow();
        };

        return new StreamOwnershipOutputs(streamOwnershipViews,
                                          streamPartitionBackfill,
                                          streamBackfillExecutor,
                                          streamReplicaSetController,
                                          streamReplicationShutdown);
    }

    /// What `streamBackfillTick` reads from the assembly built so far.
    record StreamBackfillTickInputs(PartitionBackfill streamPartitionBackfill,
                                    ExecutorService streamBackfillExecutor,
                                    Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The deferred periodic backfill re-drive.
    static Unit streamBackfillTick(StreamBackfillTickInputs in) {
        var streamPartitionBackfill = in.streamPartitionBackfill();
        var streamBackfillExecutor = in.streamBackfillExecutor();
        var defer = in.defer();
        // A4 periodic backfill re-drive: reconcilePartition fires backfill ONCE per reconcile edge, so a
        // cold-start replica that saw NO_SOURCE on its first attempt would never re-attempt and stay
        // SYNCING forever (the system:cluster-events GET /api/events -> 200 [] deadlock). Re-drive every
        // STREAM_BACKFILL_REDRIVE_INTERVAL onto the SAME backfill executor so the owner-immediate /
        // bounded-wait cold-start promotion is actually reached; CAUGHT_UP partitions are skipped.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> redriveIncompleteBackfills(streamPartitionBackfill,
                                                                                                streamBackfillExecutor),
                                                               STREAM_BACKFILL_REDRIVE_INTERVAL));

        return Unit.unit();
    }

    /// What `streamTicks` reads from the assembly built so far.
    record StreamTicksInputs(StreamPartitionManager streamPartitionManager,
                             Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The deferred WAL truncation and reshuffle reconcile ticks.
    static Unit streamTicks(StreamTicksInputs in) {
        var streamPartitionManager = in.streamPartitionManager();
        var defer = in.defer();
        // W5 WAL disk-reclamation driver: truncate every partition's write-ahead log up to its DURABLE
        // last-sealed offset so the WAL does not grow unbounded. Records <= that offset are already in
        // cold segments whose refs are in the metadata snapshot on disk (served post-restart by the tiered
        // reader), so dropping them from the WAL loses nothing; the un-sealed tail stays in the WAL.
        // truncate is threshold-lazy, so this tick is cheap when nothing new has sealed. Driven off the
        // CONTIGUOUS sealed bound (#1234: it never passes a segment that failed to seal) as REBUILT from
        // the latest snapshot file (#1345: never the live index, which runs ahead of disk until the next
        // snapshot — a crash in that window used to lose the refs and renumber the survivors).
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::truncateWalsToSealed,
                                                               WAL_TRUNCATE_INTERVAL));
        // #265 increment 5 reshuffle-lifecycle driver: each tick frees reshuffle-concurrency slots for
        // finished (self CAUGHT_UP) / owner / role-lost partitions, evaluates release candidates (role-loss
        // debounce → catch-up + owner gate → release, freeing ring memory + budget while KEEPING the WAL on
        // disk), then drains the materialization queue (system first) so budget a release just freed admits a
        // queued partition in the same tick. 5s cadence → the 2-tick flap debounce is ≈10s; a steady-state
        // tick is a cheap sweep of materialized partitions over empty queues.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::reconcileReshuffle,
                                                               STREAM_RESHUFFLE_RECONCILE_INTERVAL));
        // PR-0c ring age tick: events older than the stream's declared max age leave the ring for the sealer
        // (entity rings and streams with no declared age are skipped inside the tick).
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::evictRingsByAge,
                                                               RING_AGE_TICK_INTERVAL));

        return Unit.unit();
    }

    /// What `streamServing` reads from the assembly built so far.
    record StreamServingInputs(AetherNodeConfig config,
                               KVStore<AetherKey, AetherValue> kvStore,
                               SliceCodec nodeCodec,
                               OwnershipEpochHighWater ownershipEpochHighWater,
                               AetherNode.EpochSources epochSources,
                               HlcClock hlcClock,
                               AetherNode.ResourceProviderSetup resourceProviderSetup,
                               SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                               InvocationHandler invocationHandler,
                               DeploymentMap deploymentMap,
                               TopicSubscriptionRegistry topicSubscriptionRegistry,
                               StreamConsumerRegistry streamConsumerRegistry,
                               BooleanSupplier isLeaderSupplier,
                               Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                               OperatorWarningSink operatorWarningSink,
                               SliceInvoker sliceInvoker,
                               Functions.Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                               DelegatedStorageAdapter delegatedStorageAdapter,
                               ArrayList<MessageRouter.Entry<?>> allEntries,
                               QuorumLossDetector quorumLossDetector,
                               Consumer<QuorumLossIntent> quorumLossChain,
                               ReplicaRegistry streamReplicaRegistry,
                               SegmentIndex streamSegmentIndex,
                               CursorStore streamCursorStore,
                               TieredStreamReader streamTieredReader,
                               StreamPartitionManager streamPartitionManager,
                               WatermarkTracker streamWatermarkTracker,
                               SegmentReader streamSegmentReader,
                               RetentionEnforcer streamRetentionEnforcer,
                               GovernorFailoverHandler streamFailoverHandler,
                               StreamForwardTransport streamForwardTransport,
                               StreamingConfig streamingConfig,
                               StreamReadForwardMetrics streamReadForwardMetrics,
                               StreamForwardClient streamForwardClient,
                               ReplicaSetController streamReplicaSetController,
                               Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `streamServing` hands back for the rest of the assembly.
    record StreamServingOutputs(KvCommittedStreamOwnerSource committedStreamOwnerSource,
                                Option<LinearizableBarrier> linearizableBarrier,
                                StreamForwardHandler streamForwardHandler,
                                StreamReadRouter streamReadRouter,
                                StreamConsumerRuntime streamConsumerRuntime,
                                StreamConsumerManager streamConsumerManager,
                                ProjectionNodeSupport projectionNodeSupport) {}

    /// The committed owner source, the linearizable owner serve pipeline, the forward handler and read router, the consumer and projection machinery and the streaming coordinator.
    static StreamServingOutputs streamServing(StreamServingInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var nodeCodec = in.nodeCodec();
        var ownershipEpochHighWater = in.ownershipEpochHighWater();
        var epochSources = in.epochSources();
        var hlcClock = in.hlcClock();
        var resourceProviderSetup = in.resourceProviderSetup();
        var switchableCluster = in.switchableCluster();
        var invocationHandler = in.invocationHandler();
        var deploymentMap = in.deploymentMap();
        var topicSubscriptionRegistry = in.topicSubscriptionRegistry();
        var streamConsumerRegistry = in.streamConsumerRegistry();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterCommandApplier = in.clusterCommandApplier();
        var operatorWarningSink = in.operatorWarningSink();
        var sliceInvoker = in.sliceInvoker();
        var taskGroupOwnerResolver = in.taskGroupOwnerResolver();
        var delegatedStorageAdapter = in.delegatedStorageAdapter();
        var allEntries = in.allEntries();
        var quorumLossDetector = in.quorumLossDetector();
        var quorumLossChain = in.quorumLossChain();
        var streamReplicaRegistry = in.streamReplicaRegistry();
        var streamSegmentIndex = in.streamSegmentIndex();
        var streamCursorStore = in.streamCursorStore();
        var streamTieredReader = in.streamTieredReader();
        var streamPartitionManager = in.streamPartitionManager();
        var streamWatermarkTracker = in.streamWatermarkTracker();
        var streamSegmentReader = in.streamSegmentReader();
        var streamRetentionEnforcer = in.streamRetentionEnforcer();
        var streamFailoverHandler = in.streamFailoverHandler();
        var streamForwardTransport = in.streamForwardTransport();
        var streamingConfig = in.streamingConfig();
        var streamReadForwardMetrics = in.streamReadForwardMetrics();
        var streamForwardClient = in.streamForwardClient();
        var streamReplicaSetController = in.streamReplicaSetController();
        var defer = in.defer();
        // #345 item 1e-a: the committed-owner source, the linearizable no-op-round barrier, and the
        // shared owner-side serve pipeline. The barrier orders one no-op consensus round through the
        // cluster apply path and awaits this node's local apply of it (spec §8.1 `no-op-round`); the
        // mode is the parsed `[durable-entity] read-linearization` knob (only NO_OP_ROUND ships — a
        // `lease` value is rejected at config parse). The owner-serve pipeline is shared by the local
        // read path (StreamReadRouter) AND the forwarded read path (StreamForwardHandler) so a
        // forwarded LINEARIZABLE read is re-guarded at the owner instead of served unguarded.
        //
        // #535 HOISTED: this block used to sit BELOW the declarative-consumer wiring. It moved up as a
        // UNIT because the consumer runtime now reads through `streamReadRouter`. Only
        // `committedStreamOwnerSource` and `linearizableBarrier` were created after that point, and both
        // depend solely on state built far earlier (kvStore, clusterCommandApplier, streamingConfig), so
        // the move needs no late-binding seam. Keep the block together if it ever moves again —
        // linearizableOwnerServe and streamForwardHandler consume both of those locals.
        var committedStreamOwnerSource = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore);
        Option<LinearizableBarrier> linearizableBarrier = streamingConfig.readLinearization() == ReadLinearizationMode.NO_OP_ROUND
                                                          ? Option.some(LinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                                      streamingConfig.readForwardTimeout()))
                                                          : Option.none();
        var linearizableOwnerServe = LinearizableOwnerServe.<OffHeapRingBuffer.RawEvent> linearizableOwnerServe(config.self(),
                                                                                                                Option.some(streamReplicaRegistry),
                                                                                                                Option.some(committedStreamOwnerSource),
                                                                                                                Option.some(ownershipEpochHighWater),
                                                                                                                linearizableBarrier,
                                                                                                                (stream, partition, fromOffset, maxEvents) -> streamPartitionManager.readServing(stream,
                                                                                                                                                                                                 partition,
                                                                                                                                                                                                 fromOffset,
                                                                                                                                                                                                 maxEvents)
                                                                                                                                                                                    .async());
        var streamForwardHandler = StreamForwardHandler.streamForwardHandler(config.self(),
                                                                             streamPartitionManager,
                                                                             streamForwardTransport,
                                                                             streamingConfig.maxReadResponseBytes(),
                                                                             streamReadForwardMetrics,
                                                                             Option.some(linearizableOwnerServe),
                                                                             Option.some(streamTieredReader));
        var streamReadRouter = StreamReadRouter.streamReadRouter(streamPartitionManager,
                                                                 Option.some(streamReplicaRegistry),
                                                                 Option.some(streamForwardClient),
                                                                 config.self(),
                                                                 streamReplicaSetController::ownerFor,
                                                                 streamReadForwardMetrics,
                                                                 Option.some(committedStreamOwnerSource),
                                                                 Option.some(ownershipEpochHighWater),
                                                                 linearizableBarrier);
        // #488/#535: declarative `[streams.X]` consumer delivery. `NodeDeploymentState` writes a
        // StreamRegistrationKey per deployment; that key is NOT node-scoped, so it is a cluster-wide
        // DECLARATION, never an assignment. Which node consumes which partition is decided LOCALLY and
        // identically on every node: the partition's HRW owner when the slice is ACTIVE there, else the
        // HRW pick over the nodes where it IS active. Gating on ownership ALONE was the #488 model and
        // is exactly why a default deployment (slice on 3 of 5) delivered nothing — the owner usually
        // did not host the slice. Gating on "the ring is local" is not the alternative: REPLICA nodes
        // materialize rings too (shouldMaterialize admits OWNER and REPLICA), so that would deliver
        // every event once per replica.
        //
        // An assignee that is not the owner has no ring, so the runtime reads through the router with
        // GOVERNOR preference — local when materialized here, forwarded to the HRW owner on
        // PARTITION_NOT_LOCAL. NEAREST would be wrong: it also forwards on an EMPTY read, which for a
        // tail-polling consumer means a forward on nearly every poll.
        //
        // No role-change callback is available (onBecameReplica / onReconcilePassComplete are
        // single-consumer seams already bound above), hence the poll.
        // #1271: checkpoints carry this node's consumer-assignment token and are refused by the applier
        // once the assignment moves; a forwarding (worker) node sends a Noop barrier before re-reading the
        // verdict, since its publish resolves on the core's reply rather than its own apply.
        // #1271: the committed consumer assignment is the one authority for which node delivers a
        // (group, partition). The leader writes it from the same computation every node used to act on
        // alone; every node attaches only where the committed record names it — and the cursor store's
        // verdict re-reads it before calling a refused checkpoint Fenced (#1335 B1).
        ConsumerAssignmentWriter.CommittedAssignments committedConsumerAssignments = (stream, partition, group) -> kvStore.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                                                                partition,
                                                                                                                                                                                group),
                                                                                                                                    ConsumerAssignmentValue.class);
        // #1333: the cluster cursor store is decorated with the projection commit hook — after every
        // resolved commit the group's projection (if this node hosts one) learns the committed cursor,
        // stamped with the committing consumer's rewind epoch. The registry is keyed on the runtime's own
        // group identity (artifactBase#method) and resolves lazily against the topic subscriptions.
        var projectionRegistry = ProjectionRegistry.projectionRegistry(topicSubscriptionRegistry::allSubscriptions);
        Fn1<Option<AetherValue.StreamCursorCheckpointValue>, AetherKey.StreamCursorCheckpointKey> committedCursorReader = cursorKey -> kvStore.getTyped(cursorKey,
                                                                                                                                                        AetherValue.StreamCursorCheckpointValue.class);
        Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> cursorCommandWriter = commands -> switchableCluster.apply(commands)
                                                                                                          .mapToUnit();
        var streamClusterCursorStore = ProjectionAwareCursorStore.projectionAwareCursorStore(ClusterCursorStore.clusterCursorStore(streamCursorStore,
                                                                                                                                   config.self(),
                                                                                                                                   committedCursorReader,
                                                                                                                                   committedConsumerAssignments,
                                                                                                                                   cursorCommandWriter,
                                                                                                                                   () -> switchableCluster.current() instanceof ForwardingClusterNode),
                                                                                             projectionRegistry);
        // #386 durable pub-sub: dead letters for `topic:*` streams are durable — re-enveloped
        // group-attributed and appended to the topic's `.dlq` stream through the same confirmation
        // barrier as the source (publisher memoized per DLQ stream, full owner-forward routing so a
        // dispatch node that does not own the DLQ partition forwards instead of stalling). All
        // other streams keep the in-memory default; its durability question is a separate ticket's
        // business (ruling on #386).
        var topicDlqPublishers = new ConcurrentHashMap<String, StreamPublisher<DlqEnvelope>>();
        var streamDeadLetterSink = new RoutingDeadLetterSink(new DlqStreamSink(nodeCodec,
                                                                               streamPartitionManager,
                                                                               dlqStream -> topicDlqPublishers.computeIfAbsent(dlqStream,
                                                                                                                               name -> DefaultStreamPublisher.streamPublisher(streamPartitionManager,
                                                                                                                                                                              nodeCodec,
                                                                                                                                                                              name,
                                                                                                                                                                              1,
                                                                                                                                                                              Option.none(),
                                                                                                                                                                              ConsistencyMode.EVENTUAL,
                                                                                                                                                                              Option.none(),
                                                                                                                                                                              Option.some(streamForwardClient),
                                                                                                                                                                              Option.some(() -> taskGroupOwnerResolver.apply(TaskGroup.STREAMING)
                                                                                                                                                                                                                      .option()),
                                                                                                                                                                              Option.some(partition -> streamReplicaSetController.ownerFor(name,
                                                                                                                                                                                                                                           partition)),
                                                                                                                                                                              Option.some(config.self())))),
                                                             DeadLetterHandler.deadLetterHandler());
        var streamConsumerRuntime = StreamConsumerRuntime.streamConsumerRuntime(streamPartitionManager,
                                                                                streamDeadLetterSink,
                                                                                streamClusterCursorStore,
                                                                                StreamConsumerRuntime.validatingReader((stream, partition, fromOffset, maxEvents) -> streamReadRouter.read(stream,
                                                                                                                                                                                           partition,
                                                                                                                                                                                           fromOffset,
                                                                                                                                                                                           maxEvents,
                                                                                                                                                                                           ReadPreference.GOVERNOR),
                                                                                                                       streamReadRouter::readValidated));

        streamConsumerRuntime.operatorWarnings(operatorWarningSink);
        var streamConsumerOwnership = streamConsumerOwnership(streamPartitionManager, streamReplicaSetController);
        var consumerAssignmentAuthority = StreamConsumerManager.AssignmentAuthority.assignmentAuthority(committedConsumerAssignments,
                                                                                                        ConsumerAssignmentWriter.consumerAssignmentWriter(isLeaderSupplier,
                                                                                                                                                          epochSources.generationEpoch(),
                                                                                                                                                          hlcClock,
                                                                                                                                                          committedConsumerAssignments),
                                                                                                        commands -> switchableCluster.apply(commands)
                                                                                                                                     .mapToUnit());
        var streamConsumerManager = StreamConsumerManager.streamConsumerManager(streamConsumerRegistry,
                                                                                streamConsumerRuntime,
                                                                                sliceInvoker,
                                                                                invocationHandler,
                                                                                nodeCodec,
                                                                                streamConsumerOwnership,
                                                                                artifact -> sliceDeploymentStates(deploymentMap,
                                                                                                                  artifact),
                                                                                config.self(),
                                                                                TopicGroupDeclarationSource.topicGroupDeclarationSource(topicSubscriptionRegistry,
                                                                                                                                        streamName -> streamConsumerOwnership.partitionCount(streamName)
                                                                                                                                                                             .isPresent()),
                                                                                consumerAssignmentAuthority,
                                                                                (streamName, partition, group) -> committedCursorReader.apply(AetherKey.StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName,
                                                                                                                                                                                                            partition,
                                                                                                                                                                                                            group))
                                                                                                                                       .map(AetherValue.StreamCursorCheckpointValue::rewindEpoch));

        streamConsumerManager.setOperatorWarningSink(operatorWarningSink);
        // #1271: a node that loses quorum stops delivering at the self-fence's DETECTION, not at the drain's
        // halt — the majority is free to reassign its partitions from that moment. Re-set here because the
        // manager exists only now; the detector was armed with the same chain earlier and this replaces it
        // with the chain plus the consumer abandon.
        quorumLossDetector.setQuorumLossListener(StreamConsumerManager.abandoningOnQuorumLoss(quorumLossChain,
                                                                                              streamConsumerManager));
        // #499: the handle is retained in `periodicTasks`, which stop() cancels wholesale. A declarative
        // consumer that outlived its node would deliver into a torn-down slice.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(streamConsumerManager::reconcile,
                                                               STREAM_CONSUMER_RECONCILE_INTERVAL));
        // #1333: a committed checkpoint carrying a newer rewind epoch than the held consumer's restarts
        // that consumer on the next pass, now rather than on the 5s tick. The manager filters the key type.
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                                 streamConsumerManager::onCheckpointPut));
        // A rebuild's rewind record counts as committed once its accepted-put notification is seen here; a
        // read-back alone is overtaken by the restarted consumer's first checkpoint at the same epoch.
        var rewindWitness = CommitWitness.commitWitness();

        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, rewindWitness::onPut));
        // #1333: what a slice's ProjectionRuntime resource needs from the node — the registry above and
        // the replay cursor's collaborators (partition bounds from the local ring or forwarded to the owner
        // through the read router, the fenced checkpoint put, the committed read-back). Registered beside
        // the entity drivers, as one extension.
        var projectionNodeSupport = ProjectionNodeSupport.projectionNodeSupport(projectionRegistry,
                                                                                streamClusterCursorStore::reportFailureCount,
                                                                                streamConsumerOwnership::partitionCount,
                                                                                PartitionBounds.routed(streamReadRouter),
                                                                                cursorCommandWriter,
                                                                                committedCursorReader,
                                                                                committedConsumerAssignments,
                                                                                epochSources.incarnation()::current,
                                                                                rewindWitness);

        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> spi.registerExtension(ProjectionNodeSupport.class, projectionNodeSupport));
        var streamingCoordinator = StreamingCoordinator.streamingCoordinator(streamFailoverHandler,
                                                                             streamPartitionManager,
                                                                             streamWatermarkTracker,
                                                                             streamSegmentIndex,
                                                                             streamSegmentReader);
        // #231 Step 2: StreamingCoordinator (STREAMING) and the STORAGE adapter are leader-pinned
        // via toggle*OnLeaderChange routes appended below (after the collectRouteEntries leader-change
        // block, preserving the required activation order ... → StreamingCoordinator → StorageAdapter).
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> toggleStreamingOnLeaderChange(change, streamingCoordinator)));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> toggleStorageOnLeaderChange(change, delegatedStorageAdapter)));

        return new StreamServingOutputs(committedStreamOwnerSource,
                                        linearizableBarrier,
                                        streamForwardHandler,
                                        streamReadRouter,
                                        streamConsumerRuntime,
                                        streamConsumerManager,
                                        projectionNodeSupport);
    }

    /// What `streamWriteRouter` reads from the assembly built so far.
    record StreamWriteRouterInputs(AetherNodeConfig config,
                                   StreamPartitionManager streamPartitionManager,
                                   StreamForwardClient streamForwardClient,
                                   ReplicaSetController streamReplicaSetController) {}

    /// The stream write router.
    static StreamWriteRouter streamWriteRouter(StreamWriteRouterInputs in) {
        var config = in.config();
        var streamPartitionManager = in.streamPartitionManager();
        var streamForwardClient = in.streamForwardClient();
        var streamReplicaSetController = in.streamReplicaSetController();
        // Publish-side mirror: same forward client + HRW owner resolver the read router uses, so a
        // management publish landing on a metadata-only node write-forwards to the owner (#265) instead
        // of failing PARTITION_NOT_LOCAL on a local append.
        var streamWriteRouter = StreamWriteRouter.streamWriteRouter(streamPartitionManager,
                                                                    Option.some(streamForwardClient),
                                                                    config.self(),
                                                                    streamReplicaSetController::ownerFor);

        return streamWriteRouter;
    }

    /// What `streamForwarding` reads from the assembly built so far.
    record StreamForwardingInputs(AetherNodeConfig config,
                                  Serializer serializer,
                                  Deserializer deserializer,
                                  OwnershipEpochHighWater ownershipEpochHighWater,
                                  AetherNode.ResourceProviderSetup resourceProviderSetup,
                                  OperatorWarningSink operatorWarningSink,
                                  Functions.Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                                  ArrayList<MessageRouter.Entry<?>> allEntries,
                                  ReplicaRegistry streamReplicaRegistry,
                                  ReplicationTransport streamReplicationTransport,
                                  ReplicationManager streamReplicationManager,
                                  CursorStore streamCursorStore,
                                  TieredStreamReader streamTieredReader,
                                  StreamPartitionManager streamPartitionManager,
                                  StreamForwardClient streamForwardClient,
                                  AetherNode.StreamOwnershipViews streamOwnershipViews,
                                  PartitionBackfill streamPartitionBackfill,
                                  ExecutorService streamBackfillExecutor,
                                  ReplicaSetController streamReplicaSetController,
                                  KvCommittedStreamOwnerSource committedStreamOwnerSource,
                                  Option<LinearizableBarrier> linearizableBarrier,
                                  StreamForwardHandler streamForwardHandler) {}

    /// The stream forward routes, the replication receive handler and its routes and the stream forward extensions.
    static Unit streamForwarding(StreamForwardingInputs in) {
        var config = in.config();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var ownershipEpochHighWater = in.ownershipEpochHighWater();
        var resourceProviderSetup = in.resourceProviderSetup();
        var operatorWarningSink = in.operatorWarningSink();
        var taskGroupOwnerResolver = in.taskGroupOwnerResolver();
        var allEntries = in.allEntries();
        var streamReplicaRegistry = in.streamReplicaRegistry();
        var streamReplicationTransport = in.streamReplicationTransport();
        var streamReplicationManager = in.streamReplicationManager();
        var streamCursorStore = in.streamCursorStore();
        var streamTieredReader = in.streamTieredReader();
        var streamPartitionManager = in.streamPartitionManager();
        var streamForwardClient = in.streamForwardClient();
        var streamOwnershipViews = in.streamOwnershipViews();
        var streamPartitionBackfill = in.streamPartitionBackfill();
        var streamBackfillExecutor = in.streamBackfillExecutor();
        var streamReplicaSetController = in.streamReplicaSetController();
        var committedStreamOwnerSource = in.committedStreamOwnerSource();
        var linearizableBarrier = in.linearizableBarrier();
        var streamForwardHandler = in.streamForwardHandler();

        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForward.class,
                                                 streamForwardHandler::onPublishForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForwardResponse.class,
                                                 streamForwardClient::onPublishForwardResponse));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForward.class,
                                                 streamForwardHandler::onReadForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForwardResponse.class,
                                                 streamForwardClient::onReadForwardResponse));
        // A6: replica-side receive/apply. ReplicateEvents from an owner are landed into the local
        // partition ring offset-preserving via appendRecovered (NON-replicating — a replicated event is
        // never re-emitted, so there is no replicate→apply→replicate loop), then the highest applied
        // offset is acked back. #1230: a batch whose sender cannot be the committed owner at the batch's
        // epoch is neither applied nor acked (the same RAW committed record as the admission — not the #568
        // liveness-filtered view, which is for routing/backfill only). ReplicateAck flows into the active DefaultReplicationManager so it can
        // advance the watermark and resolve any awaitReplication(minAcks) promise. Routed on the same
        // partition message transport as the forward handler.
        var streamReplicationReceiveHandler = ReplicationReceiveHandler.replicationReceiveHandler(config.self(),
                                                                                                  streamPartitionManager::appendRecovered,
                                                                                                  streamPartitionManager::nextExpectedOffset,
                                                                                                  streamReplicationTransport,
                                                                                                  (streamName, partition) -> streamBackfillExecutor.execute(() -> streamPartitionBackfill.backfill(streamName,
                                                                                                                                                                                                   partition)),
                                                                                                  streamPartitionManager::syncReplicated,
                                                                                                  streamOwnershipViews.writeAuthority(),
                                                                                                  operatorWarningSink);
        // #1730 phase 2 (B7): a copy not yet compared with the committed owner of the current epoch acknowledges nothing.
        streamReplicationReceiveHandler.ackGate(streamPartitionManager::replicaVerified);
        // #2087: a replica serves a consumer read only up to the owner's reported visible position.
        streamReplicationReceiveHandler.commitSink(streamPartitionManager::commitAdvanced);
        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.CommitAdvance.class,
                                                 streamReplicationReceiveHandler::onCommitAdvance));
        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.ReplicateEvents.class,
                                                 streamReplicationReceiveHandler::onReplicateEvents));
        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.ReplicateAck.class,
                                                 streamReplicationManager::handleAck));
        registerStreamForwardExtensions(resourceProviderSetup,
                                        streamForwardClient,
                                        taskGroupOwnerResolver,
                                        streamReplicaSetController::ownerFor,
                                        streamPartitionManager,
                                        streamCursorStore,
                                        streamTieredReader,
                                        serializer,
                                        deserializer,
                                        config.self(),
                                        streamReplicaRegistry,
                                        committedStreamOwnerSource,
                                        ownershipEpochHighWater,
                                        linearizableBarrier);

        return Unit.unit();
    }

    /// What `retentionWatch` reads from the assembly built so far.
    record RetentionWatchInputs(KVStore<AetherKey, AetherValue> kvStore,
                                AlertManager alertManager,
                                SegmentIndex streamSegmentIndex,
                                StreamPartitionManager streamPartitionManager,
                                Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The stream retention invariant watch and its deferred tick.
    static Unit retentionWatch(RetentionWatchInputs in) {
        var kvStore = in.kvStore();
        var alertManager = in.alertManager();
        var streamSegmentIndex = in.streamSegmentIndex();
        var streamPartitionManager = in.streamPartitionManager();
        var defer = in.defer();
        // #634-4, the periodic half (owner-ruled: on-read + periodic alert). The watch binds the three
        // SHARED components directly — never a node record instance, of which two are constructed on
        // the management-enabled path — WARN-logs and raises one operator alert per partition violated
        // on two consecutive ticks, through the injection path: a per-partition violation is not a
        // metric, so the metrics-threshold path (AlertThresholdEvaluator) has nothing to evaluate.
        var retentionInvariantWatch = RetentionRoutes.RetentionInvariantWatch.retentionInvariantWatch(streamPartitionManager,
                                                                                                      streamSegmentIndex,
                                                                                                      kvStore,
                                                                                                      (name, message) -> alertManager.inject(name,
                                                                                                                                             RetentionRoutes.ALERT_SEVERITY,
                                                                                                                                             message,
                                                                                                                                             Option.none(),
                                                                                                                                             Option.none())
                                                                                                                                     .onFailure(cause -> LOG.warn("Retention alert injection failed: {}",
                                                                                                                                                                  cause.message())));

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(retentionInvariantWatch::tick,
                                                               RETENTION_INVARIANT_CHECK_INTERVAL));

        return Unit.unit();
    }

    /// The stream partition logs open through the `streams` storage instance (#1567), under its log root —
    /// the WAL dir [#resolveStreamWalDir] resolved when the instance was built; none when that dir was
    /// unusable and streams degraded to no WAL.
    static Option<AppendLog.Opener> streamLogs(StorageInstance streamStorage) {
        return streamStorage.logRoot()
                            .<AppendLog.Opener> map(_ -> AppendLog.Opener.opener(streamStorage::openLog,
                                                                                 streamStorage::inspectLog,
                                                                                 streamStorage::listLogs));
    }

    /// Build a named daemon thread for a single-thread executor's `ThreadFactory`. Extracted so the
    /// executor factories pass a method-reference-friendly single-expression lambda instead of a
    /// multi-statement block.
    private static Thread daemonThread(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);

        thread.setDaemon(true);

        return thread;
    }

    /// Periodic backfill re-drive (A4): for every partition where self is a registered, not-yet-CAUGHT_UP
    /// replica — PLUS a CAUGHT_UP non-owner replica still stale against the HRW owner (#333 write-idle
    /// residual) — re-submit `backfill` onto the dedicated backfill executor. Idempotent and bounded —
    /// {@link PartitionBackfill#redriveCandidates} enumerates the registry (owner-aware, offset-quiesced),
    /// so once everything has genuinely caught up this is a no-op. Runs off the periodic scheduler tick;
    /// backfill itself stays on `backfillExecutor` (its dedicated thread), so no competing thread pool is
    /// introduced.
    @Contract
    private static void redriveIncompleteBackfills(PartitionBackfill backfill, Executor backfillExecutor) {
        backfill.redriveCandidates()
                .forEach(key -> backfillExecutor.execute(() -> backfill.backfill(key.streamName(),
                                                                                 key.partition())));
    }

    /// #265 increment 2 materialize-on-reconcile hook. Fired (on the backfill executor) behind the
    /// controller's `onBecameReplica` seam — which triggers the instant `self` joins a partition's replica
    /// set (owner-inclusive: the HRW owner is index 0 of the owner-first replica set). It MATERIALIZES the
    /// held partition's ring FIRST, then runs the existing backfill. This resolves the spec §5.4
    /// defer-then-materialize case: a config committed before placement was known is hydrated metadata-only,
    /// and the ring is built here the moment the role resolves to OWNER/REPLICA. Materialize is idempotent
    /// (a ring built at hydrate time is a no-op) and runs before backfill so the recovered events have a
    /// ring to land in; a materialize failure is logged and backfill still attempts (the owner-append
    /// safety valve is the backstop). A PACED refusal is not lost with this log line: the manager queues it and
    /// re-drives it on every reconcile tick until the ring exists (#1805).
    @Contract
    private static void materializeThenBackfill(StreamPartitionManager manager,
                                                PartitionBackfill backfill,
                                                String streamName,
                                                int partition) {
        manager.materializePartition(streamName, partition)
               .onFailure(cause -> LOG.warn("Stream partition {}[{}] materialize-on-reconcile failed: {} — backfill will still attempt",
                                            streamName,
                                            partition,
                                            cause.message()));
        backfill.backfill(streamName, partition);
    }

    /// #445 single-sourced placement view: the backfill orchestrator ranks its owner/member view over the
    /// SAME reconciled snapshot [ReplicaSetController#roleFor] / [ReplicaSetController#ownerFor] and the
    /// registry reconcile use ([ReplicaSetController#reconciledMembers]) — NOT an independent LIVE
    /// `observer().coreNodes()` read. This removes the live-vs-reconciled split that let a node the owner
    /// registers as a replica self-classify NONE — never materializing its ring, bouncing every apply
    /// `PARTITION_NOT_LOCAL`, leaving RF≥2 structurally unreachable. Before the controller is bound / its
    /// first reconcile it falls back to the live placement projection ([#livePlacementMembers]), the SAME
    /// pre-reconcile fallback `roleFor` uses, so cold-start owner-immediate self-promotion is unaffected.
    /// #1730: the committed ISR of `(stream, partition)`; none for a record minted before #1730 (`isrVersion` 0),
    /// which carries no committed ISR, and for a partition with no record.
    static Option<List<NodeId>> committedIsr(KVStore<AetherKey, AetherValue> kvStore, String stream, int partition) {
        return committedIsrRecord(kvStore, stream, partition).map(StreamPartitionOwnershipValue::isr);
    }

    static Option<StreamPartitionOwnershipValue> committedIsrRecord(KVStore<AetherKey, AetherValue> kvStore,
                                                                    String stream,
                                                                    int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                StreamPartitionOwnershipValue.class)
                      .filter(record -> record.isrVersion() > 0);
    }

    /// #1873 (KIP-320): how an owner commits where its epoch begins, before it is activated: a guarded write of the exact
    /// committed record, witnessed by the committed leader, that records the start at `start` and, when its ring was
    /// rebuilt, first takes the next ownership term. Refusal is not a failed promise (the applier answers it with a result),
    /// so #1976: the write's own [KVCommand.TransactionResult] decides — accepted or the commit FAILS. Never a re-read of
    /// the record: on a restart the old record already names a start, so a refused commit would read as landed.
    static OwnerActivation.LineageCommit streamLineageCommit(Supplier<Option<LeaderValue>> committedLeader,
                                                             java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             HlcClock clock) {
        return (stream, partition, current, start, restarted) -> committedLeader.get()
                                                                                .fold(() -> OwnerActivation.ActivationError.NO_COMMITTED_LEADER.<Unit> promise(),
                                                                                      leader -> lineageWrite(applier,
                                                                                                             StreamPartitionOwnershipWriter.guardedOwnershipWrite(leader,
                                                                                                                                                                  stream,
                                                                                                                                                                  partition,
                                                                                                                                                                  Option.some(current),
                                                                                                                                                                  restarted
                                                                                                                                                                  ? current.restarted(start,
                                                                                                                                                                                      clock.now())
                                                                                                                                                                  : current.withEpochStart(start))));
    }

    private static Promise<Unit> lineageWrite(java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                              KVCommand.LeaderTransaction<AetherKey, AetherValue> write) {
        var transactionId = write.transactionId();

        return applier.apply(List.of(write))
                      .flatMap(results -> acceptedBy(results, transactionId)
                                          ? Promise.success(Unit.unit())
                                          : OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED.<Unit> promise());
    }

    static boolean acceptedBy(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    /// #1730: the partitions this node owns AND serves (activated for the committed record), with their record and
    /// appended head — the ISR monitor's input.
    static List<IsrMonitor.Owned> ownedPartitions(StreamPartitionManager manager,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  OwnerActivation activation,
                                                  NodeId self) {
        return manager.materializedHeads()
                      .stream()
                      .flatMap(head -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(head.streamName(),
                                                                                                                head.partition()),
                                                        StreamPartitionOwnershipValue.class)
                                              .filter(record -> record.owner()
                                                                      .equals(self) && activation.isActivated(head.streamName(),
                                                                                                              head.partition()))
                                              .map(record -> new IsrMonitor.Owned(head.streamName(),
                                                                                  head.partition(),
                                                                                  record,
                                                                                  head.head()))
                                              .stream())
                      .toList();
    }

    static List<NodeId> streamPlacementMembers(AtomicReference<ReplicaSetController> controllerRef,
                                               Supplier<List<NodeId>> placementMembers) {
        return Option.option(controllerRef.get())
                     .map(ReplicaSetController::reconciledMembers)
                     .or(placementMembers::get);
    }

    /// #1550 SINGLE SOURCE of the stream placement member set. Built once per node and handed to the
    /// stream `ReplicaSetController` and the backfill orchestrator; every other placement consumer
    /// (ownership writer, entity-ownership reconciler, consumer-group ownership, cluster-events owner gate,
    /// placement-role supplier) reads it through that controller. `LivePlacementMembersWiringTest` fails if
    /// any consumer reads the voter set directly again.
    static Supplier<List<NodeId>> livePlacementMembers(Supplier<Set<NodeId>> voters, MembershipFsm membershipFsm) {
        return () -> livePlacementMembers(voters.get(), membershipFsm);
    }

    /// #1550: the HRW stream-placement member set — the installed voters narrowed to the FSM's counted
    /// core projection (MEMBER + SUSPECT). Since #1390, `TopologyObserver.coreNodes()` returns the
    /// installed voter configuration, which is consensus identity and deliberately health-independent: a
    /// killed voter stays in it. Placing over that set kept a dead owner as the HRW winner forever, so no
    /// survivor was ever promoted. Before #1390 the same read returned the live membership projection;
    /// this restores liveness for placement without changing what consensus reads. Voters are kept as the
    /// outer bound: a fresh-identity core enters placement once the membership add installs it as a voter.
    static List<NodeId> livePlacementMembers(Set<NodeId> voters, MembershipFsm membershipFsm) {
        return voters.stream()
                     .filter(membershipFsm.coreCountedMembers()::contains)
                     .toList();
    }

    /// Partition ownership as the declarative stream-consumer manager sees it (#488/#535).
    ///
    /// `partitionCount` reads the replica catalog, which is hydrated from committed `StreamConfig`; an
    /// absent stream yields none() and the manager consumes nothing for it — the transient state before
    /// a stream's config reaches this node, not an error. `ownerOf` is the HRW owner test, the same pure
    /// placement function the partition manager itself gates materialization on. `liveMembers` is the
    /// controller's OWN reconciled view, deliberately the same one its ownership decisions are computed
    /// from, so the ownership half and the placement half of an assignment cannot disagree about which
    /// nodes are alive.
    private static StreamConsumerManager.PartitionOwnership streamConsumerOwnership(StreamPartitionManager manager,
                                                                                    ReplicaSetController controller) {
        record ownership(StreamPartitionManager manager, ReplicaSetController controller) implements StreamConsumerManager.PartitionOwnership {
            @Override
            public Option<Integer> partitionCount(String streamName) {
                return manager.replicaCatalog()
                              .streams()
                              .stream()
                              .filter(spec -> spec.name()
                                                  .equals(streamName))
                              .findFirst()
                              .map(spec -> Option.some(spec.partitions()))
                              .orElseGet(Option::none);
            }

            @Override
            public Option<NodeId> ownerOf(String streamName, int partition) {
                return controller.ownerFor(streamName, partition);
            }

            @Override
            public List<NodeId> liveMembers() {
                return controller.reconciledMembers();
            }
        }

        return new ownership(manager, controller);
    }

    /// Where `artifact` is deployed cluster-wide, from this node's locally mirrored deployment map
    /// (#535).
    ///
    /// `DeploymentMap` is fed by `NodeArtifactKey` KV notifications on EVERY node, so this answers the
    /// "where can this consumer run?" half of an assignment with no consensus round and no leader. The
    /// full per-node STATE is handed over rather than a pre-filtered node list because the manager needs
    /// to tell "ACTIVE nowhere" from "deployed but not ACTIVE yet" — only the first is a gap worth an
    /// alarm, and the second is what every normal deploy looks like for a few seconds.
    private static Map<NodeId, SliceState> sliceDeploymentStates(DeploymentMap deploymentMap, Artifact artifact) {
        return deploymentMap.byArtifact(artifact);
    }

    /// #265 increment 5: the committed-ownership release guard. Safe to release (on the ownership axis) when
    /// the committed `StreamPartitionOwnershipValue.owner` names a node OTHER than self, or no ownership record
    /// is committed yet. While the committed owner is still self, the node holds the ring until the 1d-iii
    /// reshuffle driver commits the ownership change — a deposed-but-still-committed owner must not release.
    private static boolean committedOwnerElsewhere(CommittedStreamOwnerSource source,
                                                   NodeId self,
                                                   String stream,
                                                   int partition) {
        return source.committedOwner(stream, partition)
                     .map(owner -> !owner.owner()
                                         .equals(self))
                     .or(true);
    }

    /// 1d-iii / #265 owner-reconciled BATCH driver: once per [ReplicaSetController] reconcile pass, the
    /// controller flushes the pass's reconciled `(stream, partition)` list here. The leader-only
    /// [StreamPartitionOwnershipWriter#writeOwnershipChanges] decides each pair — self-limiting to the
    /// leader (its `isLeaderSupplier` gate short-circuits BEFORE any committed-state read) and to
    /// genuinely-moved partitions (an unchanged owner's `decide` returns [Option#none]) — and returns the
    /// ownership-change Puts as ONE list. That list is applied through the consensus `ClusterNode` as a
    /// SINGLE batch, so a mass reshuffle (a membership change that shifts HRW ownership for many
    /// stream×partitions) commits ONE consensus batch per reconcile pass instead of N un-batched applies —
    /// the fan-out bound #265 promises. There is no extra leader-epoch guard around the apply (unlike the
    /// DHT bootstrap writer, whose apply runs in an async retry batch that can straddle a leader change):
    /// the decide-then-apply here is synchronous on the reconcile thread, the writer re-checks leadership
    /// immediately before deciding each pair, and consensus rejects a stale-leader apply. A failed apply
    /// is logged, never thrown, and re-arms the reconcile with backoff ([#STREAM_OWNERSHIP_RETRY_BASE]).
    @Contract
    static void driveStreamOwnership(StreamPartitionOwnershipWriter writer,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     StreamOwnershipRetry retry,
                                     List<PartitionKey> reconciled) {
        // Entity arcs are excluded HERE, not in the controller's reconcile: their LOG replicas are
        // placed by the same reconcile as any stream's (wanted), but their OWNERSHIP has exactly one
        // authority — EntityOwnershipReconciler, which places over the keyspace's hosting set. Left
        // unfiltered, this driver would re-place entity arcs over the WHOLE member view on every
        // catalog/membership edge and fight that reconcile record-for-record, parking arcs on
        // non-hosting nodes (which refuse every write) for up to one entity tick each time.
        applyStreamOwnershipBatch(applier,
                                  retry,
                                  writer.writeOwnershipChanges(EntityOwnershipReconciler.withoutEntityArcs(reconciled)));
    }

    /// Apply the reconcile pass's ownership-change Puts as ONE consensus batch. An empty batch (a
    /// follower, or a pass with no moved partitions) applies nothing and logs nothing — the empty apply
    /// is skipped rather than sent (an empty command batch would be rejected by consensus).
    @Contract
    private static void applyStreamOwnershipBatch(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                  StreamOwnershipRetry retry,
                                                  List<KVCommand<AetherKey>> commands) {
        if (commands.isEmpty()) {
            return;
        }

        applier.apply(commands)
               .onSuccess(results -> retry.settled(refusedCount(results)))
               .onFailure(cause -> retry.failed(commands.size(),
                                                cause));
    }

    /// #1730: the guarded ownership transactions of a batch the applier refused because their record moved.
    static long refusedCount(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .filter(result -> !result.accepted())
                      .count();
    }

    /// #336 reachability-evidence: when an app/blueprint stream's committed `StreamConfig` lands via
    /// consensus (e.g. `replication_factor=2 / confirmation_factor=2` at slice activation), place its replica set NOW instead
    /// of waiting for an unrelated membership/quorum edge that may never fire in a membership-stable
    /// cluster. Registered as the SECOND `StreamConfigKey` put-handler, so it runs AFTER
    /// [StreamPartitionManager#onStreamConfigPut] has hydrated the stream into `replicaCatalog()`
    /// (KVNotificationRouter dispatches handlers in registration order) — the reconcile scan therefore
    /// sees the new stream. [ReplicaSetController#reconcile] returns immediately (the work is serialized
    /// on the controller's own executor), so the KV-apply thread never blocks; it is idempotent and
    /// coalescing (safe to fire per config Put, on every applying node — each does per-node HRW
    /// placement) and writes only `StreamPartitionOwnershipKey`, never a `StreamConfigKey`, so it cannot
    /// re-trigger this edge. No-op until the controller is bound; boot-time streams are covered by the
    /// initial reconcile fired once membership is available.
    @Contract
    private static void reconcileReplicaSetOnConfigPut(AtomicReference<ReplicaSetController> controllerRef) {
        reconcileReplicaSet(controllerRef);
    }

    /// The replica-set reconcile is a pure function of CURRENT state — placement members, cluster size, the
    /// committed ownership records, the stream catalog and the leader gate on the ownership writer — and it
    /// has no other way to learn that one of them changed. #1339 and #1732 were the same defect: an input
    /// changed and nothing ran the pass. A replacement core's voter install changed the placement members
    /// after the join decision had already reconciled (#1732: RF never restored); a leadership change
    /// moved the only writer that can commit an owner after the removal decision had already reconciled with
    /// no leader to write (#1339: the partition stayed write-refused until the next membership change). The
    /// pre-existing triggers cover the membership decisions, the quorum edges and the stream-config put; these
    /// are the inputs they missed.
    ///
    ///  - voter-configuration INSTALL (genesis, §4 command, sync adoption): the placement members are the
    ///    installed voters narrowed to the FSM's counted core, and the RF clamp is the voter count. The source
    ///    calls the listener at once when a configuration is already installed, so wiring after genesis
    ///    misses nothing.
    ///  - LEADER change: the ownership writer is leader-only, so a pass that ran while no live leader existed
    ///    wrote nothing, and only a leadership edge can say the writer is now able to.
    ///  - a committed OWNERSHIP RECORD: placement follows the committed owner, so every node's registry is
    ///    re-derived when the leader's write lands (and when it is replayed on a restarted node).
    ///
    /// The fourth input — the FSM's counted set changing WITHOUT a membership decision — is wired where the
    /// FSM's transition listener already is ([#reconcileReplicaSetOnCountedBoundary]). `ReplicaSetController`
    /// coalesces triggers, so a reshuffle that commits many ownership records costs one pass.
    @Contract
    static void wireReplicaSetInputTriggers(List<MessageRouter.Entry<?>> allEntries,
                                            Runnable reconcile,
                                            Consumer<Consumer<VoterConfiguration>> voterInstallSource) {
        voterInstallSource.accept(_ -> reconcile.run());
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, _ -> reconcile.run()));
        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     _ -> reconcile.run())
                                              .build()
                                              .asRouteEntries());
    }

    /// A peer's watermark (its highest held offset; `-1` for empty) over the catch-up read class, or FAIL when
    /// the peer is unreachable. The success/failure split is the data-safety signal the backfill deadlock-break
    /// needs: a reachable peer's watermark is comparable; an unreachable peer's is unknown (and might be
    /// ahead), so self must not self-promote past it. A peer that HOLDS the partition but has not materialized
    /// it (paced, deferred) or is an owner not yet promoted is REACHABLE ([OwnerPeerReads#replicaWatermark]):
    /// the catch-up class is not gated on the peer's own promotion, and a held-unmaterialized refusal carries
    /// its durable watermark.
    private static Promise<Long> probePeerWatermark(StreamForwardClient forwardClient,
                                                    NodeId target,
                                                    String streamName,
                                                    int partition) {
        return OwnerPeerReads.replicaWatermark(forwardClient::readRemoteCatchup,
                                               target,
                                               streamName,
                                               partition,
                                               STREAM_CATCHUP_BATCH_SIZE);
    }

    /// #1555: the owner promotion gate's probe, over the catch-up read class the gate's catch-up then pulls
    /// with, starting at the peer's oldest available offset ([OwnerPeerReads#appendedWatermark]).
    private static Promise<Long> probePeerAppendedWatermark(StreamForwardClient forwardClient,
                                                            NodeId target,
                                                            String streamName,
                                                            int partition) {
        return OwnerPeerReads.appendedWatermark(forwardClient::readRemoteCatchup,
                                                target,
                                                streamName,
                                                partition,
                                                STREAM_CATCHUP_BATCH_SIZE);
    }

    /// #1555 item 8: how long a promotion may stay blocked on unreachable members before it is reported — two
    /// SWIM suspect windows, so a member that is merely slow to be declared FAULTY does not raise it.
    private static TimeSpan ownerPromotionAlarmWindow(TimeSpan suspectTimeout) {
        return StreamingConfig.ownerPromotionAlarmWindow(suspectTimeout);
    }

    static OwnerActivation.BlockAlarm ownerPromotionAlarm(OperatorWarningSink sink) {
        return new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                return raiseOwnerPromotionBlock(sink, block);
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                return resolveOwnerPromotionBlock(sink, block);
            }

            @Override
            public Unit escaped(OwnerActivation.PromotionEscape escape) {
                return raisePromotionEscape(sink, escape);
            }
        };
    }

    /// #2080: a promotion that went ahead without unreachable members is a CRITICAL operator event, one per escape, from both gates.
    /// The subject carries the gate, the skipped members and the instant, so two escapes of one partition are two events for the event
    /// layer's per-subject throttle (60 s): none is merged into an earlier one.
    private static Unit raisePromotionEscape(OperatorWarningSink sink, OwnerActivation.PromotionEscape escape) {
        return OperatorWarnings.raise(LOG,
                                      sink,
                                      OperatorWarningCode.STREAM_PROMOTION_PAST_UNREACHABLE_PEERS,
                                      escape.streamName()
                                     + "[" + escape.partition()
                                     + "]/" + escape.gate()
                                     + "/without=" + escape.skipped()
                                                           .stream()
                                                           .map(NodeId::id)
                                                           .sorted()
                                                           .toList()
                                     + "@" + System.currentTimeMillis(),
                                      "{}",
                                      escape.message());
    }

    /// What the promoted owner's backfill and its cold-start contest need from the node (#1937, #2080): where its blocks and escapes
    /// are reported, and where it reads whether a node is named in a partition's committed in-sync set. One place, so the production
    /// wiring is the code the wiring test drives.
    static Unit bindPromotionAlarm(PartitionBackfill backfill,
                                   OperatorWarningSink sink,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   TimeSpan promotionEscapeAfter) {
        var committedIsr = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore);

        backfill.blockAlarm(ownerPromotionAlarm(sink));
        backfill.promotionEscapeAfter(promotionEscapeAfter);
        backfill.committedIsr((stream, partition, node) -> committedIsr.committedIsr(stream, partition)
                                                                       .contains(node));

        return Unit.unit();
    }

    private static String partitionSubject(OwnerActivation.ActivationBlock block) {
        return block.streamName() + "[" + block.partition() + "]";
    }

    private static Unit raiseOwnerPromotionBlock(OperatorWarningSink sink, OwnerActivation.ActivationBlock block) {
        return switch (block) {
            case OwnerActivation.ActivationBlock.PeerEventExceedsReadCap oversized -> OperatorWarnings.raise(LOG,
                                                                                                             sink,
                                                                                                             OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP,
                                                                                                             partitionSubject(oversized),
                                                                                                             "{}",
                                                                                                             oversized.message());
            case OwnerActivation.ActivationBlock.HoldersUnreachable unreachable -> OperatorWarnings.raise(LOG,
                                                                                                          sink,
                                                                                                          OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE,
                                                                                                          partitionSubject(unreachable),
                                                                                                          "{}",
                                                                                                          unreachable.message());
            case OwnerActivation.ActivationBlock.LineageRefused refused -> OperatorWarnings.raise(LOG,
                                                                                                  sink,
                                                                                                  OperatorWarningCode.STREAM_OWNER_LINEAGE_REFUSED,
                                                                                                  partitionSubject(refused),
                                                                                                  "{}",
                                                                                                  refused.message());
            default -> raiseOwnerPromotionBlock(block);
        };
    }

    private static Unit resolveOwnerPromotionBlock(OperatorWarningSink sink, OwnerActivation.ActivationBlock block) {
        return switch (block) {
            case OwnerActivation.ActivationBlock.PeerEventExceedsReadCap oversized -> OperatorWarnings.raise(LOG,
                                                                                                             sink,
                                                                                                             OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP_RESOLVED,
                                                                                                             partitionSubject(oversized),
                                                                                                             "Owner promotion of {} is no longer refused for a peer's oversized event.",
                                                                                                             partitionSubject(oversized));
            case OwnerActivation.ActivationBlock.HoldersUnreachable unreachable -> OperatorWarnings.raise(LOG,
                                                                                                          sink,
                                                                                                          OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_ANSWERING,
                                                                                                          partitionSubject(unreachable),
                                                                                                          "Owner promotion of {} no longer waits for unreachable members.",
                                                                                                          partitionSubject(unreachable));
            case OwnerActivation.ActivationBlock.LineageRefused refused -> OperatorWarnings.raise(LOG,
                                                                                                  sink,
                                                                                                  OperatorWarningCode.STREAM_OWNER_LINEAGE_COMMITTED,
                                                                                                  partitionSubject(refused),
                                                                                                  "Owner promotion of {} is no longer refused at its epoch-start commit.",
                                                                                                  partitionSubject(refused));
            default -> Unit.unit();
        };
    }

    private static Unit raiseOwnerPromotionBlock(OwnerActivation.ActivationBlock block) {
        // TODO(#1574): raise as a CRITICAL OperatorWarning once #1574 merges. Until then the operator-visible
        // surfaces are this WARN and the partition status read (`ownerActivationBlock` on STREAM_REPLICAS).
        LOG.warn("CRITICAL: {}", block.message());

        return Unit.unit();
    }

    /// StreamingCoordinator — TaskGroup STREAMING.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleStreamingOnLeaderChange(LeaderNotification.LeaderChange change,
                                                      StreamingCoordinator streamingCoordinator) {
        if (change.localNodeIsLeader()) {
            streamingCoordinator.activate();
        } else {
            streamingCoordinator.deactivate();
        }
    }

    /// StorageAdapter — TaskGroup STORAGE (harmless for noOp, correct for the real adapter).
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleStorageOnLeaderChange(LeaderNotification.LeaderChange change,
                                                    DelegatedStorageAdapter storageAdapter) {
        if (change.localNodeIsLeader()) {
            storageAdapter.activate();
        } else {
            storageAdapter.deactivate();
        }
    }

    private static StreamForwardTransport createStreamForwardTransport(ClusterNetwork network) {
        return new StreamForwardTransport() {
            @Contract
            @Override
            public void send(NodeId target, StreamForwardMessage message) {
                network.send(target, message);
            }

            @Contract
            @Override
            public void send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
                network.send(target, message, callerWait);
            }
        };
    }

    private static void registerStreamForwardExtensions(ResourceProviderSetup resourceProviderSetup,
                                                        StreamForwardClient forwardClient,
                                                        Fn1<Result<NodeId>, TaskGroup> ownerResolver,
                                                        Fn2<Option<NodeId>, String, Integer> partitionOwnerResolver,
                                                        StreamPartitionManager streamPartitionManager,
                                                        CursorStore streamCursorStore,
                                                        TieredStreamReader streamTieredReader,
                                                        Serializer serializer,
                                                        Deserializer deserializer,
                                                        NodeId self,
                                                        ReplicaRegistry streamReplicaRegistry,
                                                        CommittedStreamOwnerSource committedStreamOwnerSource,
                                                        OwnershipEpochHighWater ownershipEpochHighWater,
                                                        Option<LinearizableBarrier> linearizableBarrier) {
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerForwardExtensionsOnSpi(spi,
                                                                              forwardClient,
                                                                              ownerResolver,
                                                                              partitionOwnerResolver,
                                                                              streamPartitionManager,
                                                                              streamCursorStore,
                                                                              streamTieredReader,
                                                                              serializer,
                                                                              deserializer,
                                                                              self,
                                                                              streamReplicaRegistry,
                                                                              committedStreamOwnerSource,
                                                                              ownershipEpochHighWater,
                                                                              linearizableBarrier));
    }

    private static void registerForwardExtensionsOnSpi(SpiResourceProvider spi,
                                                       StreamForwardClient forwardClient,
                                                       Fn1<Result<NodeId>, TaskGroup> ownerResolver,
                                                       Fn2<Option<NodeId>, String, Integer> partitionOwnerResolver,
                                                       StreamPartitionManager streamPartitionManager,
                                                       CursorStore streamCursorStore,
                                                       TieredStreamReader streamTieredReader,
                                                       Serializer serializer,
                                                       Deserializer deserializer,
                                                       NodeId self,
                                                       ReplicaRegistry streamReplicaRegistry,
                                                       CommittedStreamOwnerSource committedStreamOwnerSource,
                                                       OwnershipEpochHighWater ownershipEpochHighWater,
                                                       Option<LinearizableBarrier> linearizableBarrier) {
        spi.registerExtension(StreamForwardClient.class, forwardClient);
        spi.registerExtension(StreamPartitionManager.class, streamPartitionManager);
        spi.registerExtension(CursorStore.class, streamCursorStore);
        spi.registerExtension(TieredStreamReader.class, streamTieredReader);
        spi.registerExtension(Serializer.class, serializer);
        spi.registerExtension(Deserializer.class, deserializer);
        // A6: self identity so the app StreamAccessFactory can wire owner-routed publish (compare the
        // resolved HRW owner against this node).
        spi.registerExtension(NodeId.class, self);
        // A6 read-forwarding: expose the node-level replica registry so the app StreamAccessFactory wires
        // NEAREST app-stream reads — a non-replica consumer forwards to the partition's HRW owner instead
        // of reading its own empty local partition (the asymmetry that left GOVERNOR-default reads empty).
        spi.registerExtension(ReplicaRegistry.class, streamReplicaRegistry);
        // #47: the STREAMING publish resolver carries BOTH the arg-less leader resolver (retained for
        // the STRONG / forward-fallback path) AND the (stream, partition)-aware HRW owner-resolver from
        // the ReplicaSetController. App-stream EVENTUAL publishes now route to the HRW owner — the SAME
        // placement authority that owns the replica set — instead of the consensus leader. The general
        // taskGroupOwnerResolver (leader) is unchanged; only the STREAMING publish path is repointed.
        spi.registerExtension(StreamPublisherFactory.GovernorResolver.class,
                              new StreamPublisherFactory.GovernorResolver(() -> ownerResolver.apply(TaskGroup.STREAMING)
                                                                                             .option(),
                                                                          Option.some(partitionOwnerResolver)));
        // #345 item 1e-c: expose the LINEARIZABLE pipeline components so the app StreamAccessFactory
        // wires a typed LINEARIZABLE read through the SAME committed-owner routing + owner-side
        // fence/round/catch-up pipeline as the raw StreamReadRouter path (1e-a). The barrier is present
        // only when [durable-entity] read-linearization is NO_OP_ROUND; when absent the typed
        // LINEARIZABLE arm degrades to the replica-routed read exactly as the raw path does.
        spi.registerExtension(CommittedStreamOwnerSource.class, committedStreamOwnerSource);
        spi.registerExtension(OwnershipEpochHighWater.class, ownershipEpochHighWater);
        linearizableBarrier.onPresent(barrier -> spi.registerExtension(LinearizableBarrier.class, barrier));
    }

    /// How far a stream's sealed log may be reclaimed without destroying state something still needs
    /// (#345 I3).
    ///
    /// Non-entity streams get [Long#MAX_VALUE] — no floor, so the age policy alone decides and retention
    /// behaves exactly as it did before this existed.
    ///
    /// An `entity:<keyspace>` partition instead reports its COMMITTED checkpoint's `throughOffset`.
    /// Everything at or below that is already folded into a durable snapshot and is genuinely garbage;
    /// everything above it is still the only copy of a mutation. Without this the enforcer — which is
    /// built once per node with a single age policy and never reads per-stream config — would delete an
    /// entity's segments a fixed interval after its last write, silently destroying the state of any key
    /// not recently touched.
    ///
    /// A keyspace with NO committed checkpoint reclaims nothing (`-1`): nothing has been folded anywhere
    /// yet, so every record is still load-bearing. The log grows until the first checkpoint, which is the
    /// correct trade — bounded growth is the checkpoint's job, and until one exists the only safe answer
    /// is to keep everything.
    private static long entityRetentionFloor(KVStore<AetherKey, AetherValue> kvStore, String stream, int partition) {
        if (!stream.startsWith(EntityPartitionArc.ARC_PREFIX)) {
            return Long.MAX_VALUE;
        }

        var keyspace = stream.substring(EntityPartitionArc.ARC_PREFIX.length());

        return kvStore.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace, partition),
                                EntityFoldCheckpointValue.class)
                      .map(EntityFoldCheckpointValue::throughOffset)
                      .or(-1L);
    }

    record unused() implements StreamAssembly {}
}
