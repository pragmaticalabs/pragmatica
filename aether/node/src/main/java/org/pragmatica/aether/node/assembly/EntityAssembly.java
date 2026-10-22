// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.config.ReadLinearizationMode;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.KvCommittedPartitionOwnerSource;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNode.ResourceProviderSetup;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.EntityCheckpointLagMetric;
import org.pragmatica.aether.node.EntityOwnershipReconciler;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.node.entityforward.CommittedEntityStake;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage;
import org.pragmatica.aether.node.entityforward.EntityForwardService;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.resource.entity.EntityKeyspaceRegistrar;
import org.pragmatica.aether.resource.entity.EntityLinearizableBarrier;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.resource.entity.EntityOwnerForward;
import org.pragmatica.aether.resource.entity.EntityTimerDriver;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.ENTITY_CHECKPOINT_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.ENTITY_FORWARD_TIMEOUT;
import static org.pragmatica.aether.node.AetherNode.ENTITY_OWNERSHIP_RECONCILE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.ENTITY_RECONCILE_KICK;
import static org.pragmatica.aether.node.AetherNode.ENTITY_TIMER_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.streamCatchupView;
import static org.pragmatica.aether.node.AetherNode.streamIsrInputs;


/// The entity unit of the node assembly: the entity forward service and its routes, the ownership reconciler, the
/// checkpoint and timer drivers, the stream-backed log substrate, the entity runtime extensions and their deferred ticks.
/// Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface EntityAssembly {
    /// What `entityForwarding` reads from the assembly built so far.
    record EntityForwardingInputs(AetherNodeConfig config,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  ArrayList<MessageRouter.Entry<?>> allEntries) {}

    /// The entity forward service and the routes that carry entity forwards between nodes.
    static EntityForwardService entityForwarding(EntityForwardingInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var allEntries = in.allEntries();
        // Entity owner-forwarding (#596). Same `network::send` seam the stream forward client uses, so a
        // command reaches the committed owner over the ordinary FORWARD lane rather than a new transport.
        var entityForwardService = EntityForwardService.entityForwardService(config.self(),
                                                                             clusterNode.network()::sendOutcome,
                                                                             ENTITY_FORWARD_TIMEOUT,
                                                                             CommittedEntityStake.committedEntityStake(kvStore,
                                                                                                                       config.self()));

        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityUpdateForward.class,
                                                 entityForwardService::onEntityUpdateForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityCreateForward.class,
                                                 entityForwardService::onEntityCreateForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityDeleteForward.class,
                                                 entityForwardService::onEntityDeleteForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityUpdateForwardResponse.class,
                                                 entityForwardService::onEntityUpdateForwardResponse));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityGetForward.class,
                                                 entityForwardService::onEntityGetForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityGetForwardResponse.class,
                                                 entityForwardService::onEntityGetForwardResponse));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityScheduleTimerForward.class,
                                                 entityForwardService::onEntityScheduleTimerForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityScheduleTimerForwardResponse.class,
                                                 entityForwardService::onEntityScheduleTimerForwardResponse));
        // Cancel answers with EntityUpdateForwardResponse, already routed above — no response route of its own.
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityCancelTimerForward.class,
                                                 entityForwardService::onEntityCancelTimerForward));

        return entityForwardService;
    }

    /// What `entityRuntime` reads from the assembly built so far.
    record EntityRuntimeInputs(AetherNodeConfig config,
                               KVStore<AetherKey, AetherValue> kvStore,
                               RabiaNode<KVCommand<AetherKey>> clusterNode,
                               OwnershipEpochHighWater ownershipEpochHighWater,
                               AetherNode.EpochSources epochSources,
                               HlcClock hlcClock,
                               AetherNode.ResourceProviderSetup resourceProviderSetup,
                               ClusterSyncCollector metricsCollector,
                               BooleanSupplier isLeaderSupplier,
                               Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                               AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                               OperatorWarningSink operatorWarningSink,
                               ArrayList<MessageRouter.Entry<?>> allEntries,
                               ReplicaRegistry streamReplicaRegistry,
                               SegmentIndex streamSegmentIndex,
                               StorageInstance streamStorage,
                               TieredStreamReader streamTieredReader,
                               SegmentSealer streamSegmentSealer,
                               StreamPartitionManager streamPartitionManager,
                               StreamingConfig streamingConfig,
                               ReplicaSetController streamReplicaSetController,
                               EntityForwardService entityForwardService,
                               Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The entity ownership reconciler, the checkpoint and timer drivers, the log substrate, the runtime extensions and the deferred ticks.
    static EntityCheckpointDriver entityRuntime(EntityRuntimeInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var ownershipEpochHighWater = in.ownershipEpochHighWater();
        var epochSources = in.epochSources();
        var hlcClock = in.hlcClock();
        var resourceProviderSetup = in.resourceProviderSetup();
        var metricsCollector = in.metricsCollector();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterCommandApplier = in.clusterCommandApplier();
        var clusterEventsControllerRef = in.clusterEventsControllerRef();
        var operatorWarningSink = in.operatorWarningSink();
        var allEntries = in.allEntries();
        var streamReplicaRegistry = in.streamReplicaRegistry();
        var streamSegmentIndex = in.streamSegmentIndex();
        var streamStorage = in.streamStorage();
        var streamTieredReader = in.streamTieredReader();
        var streamSegmentSealer = in.streamSegmentSealer();
        var streamPartitionManager = in.streamPartitionManager();
        var streamingConfig = in.streamingConfig();
        var streamReplicaSetController = in.streamReplicaSetController();
        var entityForwardService = in.entityForwardService();
        var defer = in.defer();
        // #345 I1(d): the durable-entity side of the same SPI channel. `DurableEntityFactory` refuses to
        // provision without these, so a node that skipped this call fails an entity slice loudly at deploy
        // instead of handing it a private unfenced map (the five-writers-per-key shape I0 measured). The
        // barrier is gated on the SAME `[durable-entity] read-linearization` knob as the stream barrier
        // above and reuses the same applier and timeout budget, so entity and stream linearizable rounds
        // cannot disagree about how long a round may take.
        // The entity-arc ownership reconciler (see EntityOwnershipReconciler for the two-halves
        // contract, the 02w hosting-set rationale, and the exclusive-authority boundary with the stream
        // ownership driver). Its writer shares the stream writer's leader gate, epoch discipline and
        // record family; it differs ONLY in the HrwOwner, which the reconciler binds to the keyspace's
        // registered hosts ∩ the SAME reconciled member snapshot stream placement uses (#445 single
        // placement view). The clusterNode::isActive argument is the removal half's prune gate (#702) —
        // the same live consensus-active sample reconcileNodeActivation reads. Without it, a
        // constructed-but-never-started node whose KV replica carries committed self-registrations
        // mass-removes them into consensus off its empty declared set.
        var entityOwnershipReconciler = EntityOwnershipReconciler.entityOwnershipReconciler(kvStore,
                                                                                            config.self(),
                                                                                            streamReplicaSetController::reconciledMembers,
                                                                                            clusterNode::isActive,
                                                                                            entityArcOwner -> StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeaderSupplier,
                                                                                                                                                                            epochSources.generationEpoch(),
                                                                                                                                                                            hlcClock,
                                                                                                                                                                            (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                                                                                            partition),
                                                                                                                                                                                                                    StreamPartitionOwnershipValue.class),
                                                                                                                                                                            entityArcOwner,
                                                                                                                                                                            streamIsrInputs(clusterEventsControllerRef),
                                                                                                                                                                            () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                                                                                                                   LeaderValue.class)),
                                                                                            clusterCommandApplier,
                                                                                            ENTITY_RECONCILE_KICK,
                                                                                            EntityOwnershipReconciler.SETTLE_TICKS,
                                                                                            EntityOwnershipReconciler.FIRST_MINT_CEILING_TICKS,
                                                                                            ENTITY_OWNERSHIP_RECONCILE_INTERVAL,
                                                                                            System::nanoTime,
                                                                                            operatorWarningSink);
        // A committed registration REMOVAL is a hosting-set shrink, which is the one edge the leader's mint
        // must not wait a tick for: until it re-mints, the retracting node is still the committed owner of
        // arcs it can no longer serve. Every node sees the notification; the writer's own leader gate
        // decides who acts, so a follower's pass emits nothing.
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                                 entityOwnershipReconciler::onRegistrationRemoved));
        // #1302/#1330: every checkpoint tick reports this node's largest checkpoint lag — over partitions it
        // OWNS, measured from the committed checkpoint in KV — into the node metrics map, where the alert
        // threshold path (DashboardMetricsPublisher -> AlertManager.checkThreshold) evaluates it.
        var entityCheckpointDriver = EntityCheckpointDriver.entityCheckpointDriver(EntityCheckpointLagMetric.sinkFor(metricsCollector),
                                                                                   EntityCheckpointLagMetric.committedCheckpoints(kvStore));
        var entityTimerDriver = EntityTimerDriver.entityTimerDriver();
        // #345 I3: entity state lives on a fenced, fsync-durable, replicated stream partition, and its
        // checkpoints are blocks in stream storage pointed at from consensus KV. The catch-up source is
        // the same one the partition manager gates replica release on — a fold must not trust its local
        // head offset until the cluster agrees this node's copy is complete.
        var entityLogSubstrate = StreamEntityLogSubstrate.streamEntityLogSubstrate(streamPartitionManager,
                                                                                   (stream, partition) -> streamCatchupView(streamReplicaRegistry,
                                                                                                                            config.self(),
                                                                                                                            stream,
                                                                                                                            partition),
                                                                                   streamTieredReader,
                                                                                   streamSegmentIndex,
                                                                                   streamSegmentSealer,
                                                                                   streamStorage,
                                                                                   kvStore,
                                                                                   clusterCommandApplier);

        registerEntityExtensions(resourceProviderSetup,
                                 kvStore,
                                 ownershipEpochHighWater,
                                 entityOwnershipReconciler,
                                 streamingConfig.readLinearization() == ReadLinearizationMode.NO_OP_ROUND
                                 ? Option.some(EntityLinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                   streamingConfig.readForwardTimeout()))
                                 : Option.none(),
                                 entityLogSubstrate,
                                 entityCheckpointDriver,
                                 entityTimerDriver,
                                 entityForwardService);
        // The only thing that ever bounds an entity log: until a partition is checkpointed, the retention
        // floor refuses to reclaim any of its segments (correctly — nothing has been folded anywhere), so
        // a node that never ticks grows its entity logs without limit.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(entityCheckpointDriver::tick, ENTITY_CHECKPOINT_INTERVAL));
        // Scheduled here with the other assembly-time periodic tasks, deliberately rather than
        // special-cased: #644 tracks the whole family (tasks started at assembly run on nodes that never
        // start), and a driver that opted out of the pattern would hide from that sweep. The per-partition
        // owner gate ahead of the fire path is what is expected to make a tick on a never-started node
        // inert; that expectation is recorded on #644 to be VERIFIED by the sweep, not assumed here.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(entityTimerDriver::tick, ENTITY_TIMER_INTERVAL));
        // #345 I1 narrow C: mint the ownership records the entity fence reads, reusing the stream-side
        // writer mechanism, record family, leader gate and batching rather than an entity-specific
        // driver. The keyspace's LOG is a real stream since I3 (StreamEntityLogSubstrate.ensureLog),
        // but its REGISTRATION deliberately is not derived from stream records, and its OWNERSHIP has
        // exactly one authority — this reconciler; the stream ownership driver excludes entity arcs
        // (see driveStreamOwnership).
        //
        // The 02w hosting-set fix: an entity write is served only by a node hosting the keyspace's
        // declaring slice, so with `instances` below the cluster size the previously-shared writer
        // minted owners that refused every write they were handed (22/40 creates in the 02w run). The
        // reconciler's candidate set is the keyspace's registered hosts intersected with the live
        // member snapshot, so a dead host is never chosen and failover re-placement stays within the
        // hosting set by construction.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(entityOwnershipReconciler::tick,
                                                               ENTITY_OWNERSHIP_RECONCILE_INTERVAL));

        return entityCheckpointDriver;
    }

    /// #345 I1(d): expose the durable-entity fence collaborators on the SAME node-wide SPI extension
    /// channel the stream side uses, so `DurableEntityFactory` can build a `PartitionFencedDurableEntity`
    /// — per-`(keyspace, partition)` write fence AND owner-routed linearizable reads — instead of the
    /// unfenced in-process map it returned before this landed.
    ///
    /// `NodeId`, `Serializer`, `Deserializer` and `OwnershipEpochHighWater` are already registered by
    /// [#registerForwardExtensionsOnSpi]; only the three entity-specific collaborators are added here. The
    /// entity's `EntityPartitionArc` is deliberately NOT one of them: its partition count is per-keyspace
    /// config, so the factory builds it inside `provision`.
    private static void registerEntityExtensions(ResourceProviderSetup resourceProviderSetup,
                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                 OwnershipEpochHighWater ownershipEpochHighWater,
                                                 EntityKeyspaceRegistrar entityKeyspaceRegistrar,
                                                 Option<EntityLinearizableBarrier> entityBarrier,
                                                 EntityLogSubstrate entityLogSubstrate,
                                                 EntityCheckpointDriver entityCheckpointDriver,
                                                 EntityTimerDriver entityTimerDriver,
                                                 EntityForwardService entityForwardService) {
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerEntityExtensionsOnSpi(spi,
                                                                             kvStore,
                                                                             ownershipEpochHighWater,
                                                                             entityKeyspaceRegistrar,
                                                                             entityBarrier,
                                                                             entityLogSubstrate,
                                                                             entityCheckpointDriver,
                                                                             entityTimerDriver,
                                                                             entityForwardService));
    }

    private static void registerEntityExtensionsOnSpi(SpiResourceProvider spi,
                                                      KVStore<AetherKey, AetherValue> kvStore,
                                                      OwnershipEpochHighWater ownershipEpochHighWater,
                                                      EntityKeyspaceRegistrar entityKeyspaceRegistrar,
                                                      Option<EntityLinearizableBarrier> entityBarrier,
                                                      EntityLogSubstrate entityLogSubstrate,
                                                      EntityCheckpointDriver entityCheckpointDriver,
                                                      EntityTimerDriver entityTimerDriver,
                                                      EntityForwardService entityForwardService) {
        // The entity's DURABLE backing (#345 I3). Entity state is the fold of a fenced, fsync-durable,
        // replicated log — one stream named entity:<keyspace> per keyspace — so it now survives both a
        // restart of its owner and the loss of that owner. This replaces the MemoryStorageEngine that
        // backed the entity through I1: that engine was HA-only, its contents died with the process, and
        // the epoch fence that sat on it has moved to the log's own append gate over the SAME arc.
        spi.registerExtension(EntityLogSubstrate.class, entityLogSubstrate);
        // One committed-ownership lookup drives BOTH entity halves: admission reads `owner` from it to
        // decide who may write, and the LINEARIZABLE read path routes on the same record, so a write
        // admission and a read route can never come from different records. Reads the same Rabia-backed
        // StreamPartitionOwnershipValue family the stream side uses — the entity keyspace reuses those
        // arcs rather than minting a record type.
        spi.registerExtension(CommittedPartitionOwnerSource.class,
                              KvCommittedPartitionOwnerSource.kvCommittedPartitionOwnerSource(kvStore));
        // Absent barrier is NOT fatal: BOUNDED_STALE reads still work and a LINEARIZABLE read is refused
        // per-read with EntityError.LinearizableUnavailable (#345 I1 owner ruling — a missing barrier
        // costs freshness, a missing fence costs safety, so only the latter refuses the whole resource).
        entityBarrier.onPresent(barrier -> spi.registerExtension(EntityLinearizableBarrier.class, barrier));
        // The keyspace declaration seam. Mandatory, like the fence collaborators: without it a provisioned
        // entity would never reach the leader's ownership reconcile, so every write would refuse forever
        // with the transient cause — a permanent outage wearing a "retry me" message. Loud at deploy beats
        // that.
        spi.registerExtension(EntityKeyspaceRegistrar.class, entityKeyspaceRegistrar);
        // The checkpoint driver. Optional at the factory (an absent driver costs boundedness, not
        // correctness) but ALWAYS registered here — without it no entity log is ever reclaimed and the
        // retention floor pins every segment forever.
        spi.registerExtension(EntityCheckpointDriver.class, entityCheckpointDriver);
        // The timer driver, registered on the same terms and for the same reason: the factory treats an
        // absent driver as a TIMELINESS cost rather than a refusal, so registering it here is what makes
        // that absence an assembly bug rather than a supported mode.
        spi.registerExtension(EntityTimerDriver.class, entityTimerDriver);
        // Owner-forwarding (#596), both halves of the same service: the SENDER a non-owner uses to reach
        // the committed owner, and the REGISTRY an arriving command is applied through. Registered
        // together because a node that can send but not receive would forward into silence.
        spi.registerExtension(EntityOwnerForward.class, entityForwardService);
        spi.registerExtension(EntityForwardRegistry.class, entityForwardService);
    }

    record unused() implements EntityAssembly {}
}
