// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityPlacementOperationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageRouter;


/// The committed facts the two deployment FSMs consume, as KV notifications (phase-3 design 4.1, 5.1, B1, B8; W-01..W-03).
///
/// **The stamp is read synchronously.** Every handler reads `KVStore.notificationRevision()` and `KVStore.isReplaying()` **inside the
/// dispatch, on the dispatching thread, before it enqueues anything**: both are thread-scoped to the dispatch (design 4.4), so a hop would read
/// `0` / `false` and every such input would then be dropped at or below the snapshot revision, or kept after it, wrongly. The interpreter's
/// consumer applies the core drop rule to the stamp; nothing here steps a FSM.
///
/// **Cluster FSM.** One route per key family `F2/Indexer` reads, put and remove (`ClusterConfigKey` is indexed on put only), plus the committed
/// `LeaderValue` as `LeaderObserved`, which is the only source of `viewSequence` (B8, CI-16). The set is listed here and pinned against the
/// Indexer's own switch by `Fsm2RoutesTest` (W-01), so a key family added to the Indexer without a route is red.
///
/// **Node FSM.** `SliceDesiredKey` puts and removes for this node only (NI-2, the own-node filter, W-02), `SliceObservedKey` for every node with
/// the boot token the registry knows for the writer **at delivery** (NI-3, W-03), and the committed `SliceTargetKey` and `VersionRoutingKey`
/// (NI-4).
public final class Fsm2Routes {
    /// Where an FSM's KV-sourced inputs go: the interpreter's `deliverKv`.
    public interface Sink<I> {
        boolean deliverKv(I input, long revision, boolean replay);
    }

    /// What the routes are built from. `revision` and `replaying` are the store's thread-scoped stamp accessors.
    public record Targets(NodeId self,
                          LongSupplier revision,
                          BooleanSupplier replaying,
                          ToLongFunction<NodeId> tokenOf,
                          Sink<NodeFsmInput> node,
                          Sink<ClusterInput> cluster) {}

    private final Targets targets;

    private Fsm2Routes(Targets targets) {
        this.targets = targets;
    }

    public static Fsm2Routes fsm2Routes(Targets targets) {
        return new Fsm2Routes(targets);
    }

    /// The key families the cluster FSM indexes on a put.
    public static final List<Class<? extends AetherKey>> CLUSTER_PUT_FAMILIES = List.of(AppBlueprintKey.class,
                                                                                        SliceTargetKey.class,
                                                                                        VersionRoutingKey.class,
                                                                                        SliceDesiredKey.class,
                                                                                        SliceObservedKey.class,
                                                                                        ActivationDirectiveKey.class,
                                                                                        CommunityKey.class,
                                                                                        GovernorAnnouncementKey.class,
                                                                                        NodeDepartureKey.class,
                                                                                        ArtifactJudgmentKey.class,
                                                                                        DeploymentApplyKey.class,
                                                                                        DeploymentOutcomeKey.class,
                                                                                        SchemaVersionKey.class,
                                                                                        SchemaMigrationLockKey.class,
                                                                                        NodeRoutesKey.class,
                                                                                        NodePlacementKey.class,
                                                                                        CommunityPlacementOperationKey.class,
                                                                                        ClusterConfigKey.class);

    /// The key families the cluster FSM indexes on a remove: the put families without `ClusterConfigKey`.
    public static final List<Class<? extends AetherKey>> CLUSTER_REMOVE_FAMILIES = CLUSTER_PUT_FAMILIES.stream()
                                                                                                       .filter(type -> type != ClusterConfigKey.class)
                                                                                                       .toList();

    /// Registers every route on `builder`, in the order the families are listed (the order among handlers of one family is not load-bearing:
    /// each handler only enqueues).
    @Contract
    public void register(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        CLUSTER_PUT_FAMILIES.forEach(type -> registerPut(builder, type));
        CLUSTER_REMOVE_FAMILIES.forEach(type -> registerRemove(builder, type));
        builder.onPut(SliceDesiredKey.class, this::desiredPut)
               .onRemove(SliceDesiredKey.class, this::desiredRemove)
               .onPut(SliceObservedKey.class, this::observedPut)
               .onRemove(SliceObservedKey.class, this::observedRemove)
               .onPut(SliceTargetKey.class, this::targetPut)
               .onRemove(SliceTargetKey.class, this::targetRemove)
               .onPut(VersionRoutingKey.class, this::routingPut)
               .onRemove(VersionRoutingKey.class, this::routingRemove);
    }

    /// The route for the committed `LeaderValue` (B8): not an `AetherKey`, so it rides the plain notification channel.
    public List<MessageRouter.Entry<?>> leaderEntries() {
        return List.of(MessageRouter.Entry.route(ValuePut.class, this::leaderPut));
    }

    // -- the cluster FSM -------------------------------------------------------------------------------------------
    private void registerPut(KVNotificationRouter.Builder<AetherKey, AetherValue> builder,
                             Class<? extends AetherKey> type) {
        builder.<AetherKey, AetherValue> onPut(asKeyClass(type), this::clusterPut);
    }

    private void registerRemove(KVNotificationRouter.Builder<AetherKey, AetherValue> builder,
                                Class<? extends AetherKey> type) {
        builder.<AetherKey, AetherValue> onRemove(asKeyClass(type), this::clusterRemove);
    }

    /// The router dispatches on the runtime class it was given, so the generic parameter is only for the compiler.
    @SuppressWarnings("unchecked")
    private static Class<AetherKey> asKeyClass(Class<? extends AetherKey> type) {
        return (Class<AetherKey>) type;
    }

    private void clusterPut(ValuePut<? extends AetherKey, ? extends AetherValue> put) {
        targets.cluster()
               .deliverKv(new ClusterInput.KvPut(put.cause().key(),
                                                 put.cause().value()),
                          targets.revision().getAsLong(),
                          targets.replaying().getAsBoolean());
    }

    private void clusterRemove(ValueRemove<? extends AetherKey, ? extends AetherValue> remove) {
        targets.cluster()
               .deliverKv(new ClusterInput.KvRemove(remove.cause().key()),
                          targets.revision().getAsLong(),
                          targets.replaying().getAsBoolean());
    }

    @SuppressWarnings("rawtypes")
    private void leaderPut(ValuePut put) {
        if (put.cause().key() instanceof LeaderKey && put.cause().value() instanceof LeaderValue leader) {
            targets.cluster()
                   .deliverKv(new ClusterInput.LeaderObserved(leader),
                              targets.revision().getAsLong(),
                              targets.replaying().getAsBoolean());
        }
    }

    // -- the node FSM ------------------------------------------------------------------------------------------------
    private void desiredPut(ValuePut<SliceDesiredKey, SliceDesiredValue> put) {
        var key = put.cause().key();

        if (key.nodeId().equals(targets.self())) {
            node(new NodeFsmInput.DesiredPut(key.artifact(),
                                             put.cause().value()));
        }
    }

    private void desiredRemove(ValueRemove<SliceDesiredKey, SliceDesiredValue> remove) {
        var key = remove.cause().key();

        if (key.nodeId().equals(targets.self())) {
            node(new NodeFsmInput.DesiredRemove(key.artifact()));
        }
    }

    private void observedPut(ValuePut<SliceObservedKey, SliceObservedValue> put) {
        var key = put.cause().key();

        node(new NodeFsmInput.ObservedPut(key.nodeId(),
                                          key.artifact(),
                                          put.cause().value(),
                                          targets.tokenOf().applyAsLong(key.nodeId())));
    }

    private void observedRemove(ValueRemove<SliceObservedKey, SliceObservedValue> remove) {
        var key = remove.cause().key();

        node(new NodeFsmInput.ObservedRemove(key.nodeId(), key.artifact()));
    }

    private void targetPut(ValuePut<SliceTargetKey, SliceTargetValue> put) {
        node(new NodeFsmInput.TargetPut(put.cause().key().artifactBase(),
                                        put.cause().value().currentVersion()));
    }

    private void targetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> remove) {
        node(new NodeFsmInput.TargetRemove(remove.cause().key().artifactBase()));
    }

    private void routingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> put) {
        var value = put.cause().value();

        node(new NodeFsmInput.RoutingPut(put.cause().key().artifactBase(),
                                         value.oldVersion(),
                                         value.newVersion()));
    }

    private void routingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> remove) {
        node(new NodeFsmInput.RoutingRemove(remove.cause().key().artifactBase()));
    }

    private void node(NodeFsmInput input) {
        targets.node().deliverKv(input,
                                 targets.revision().getAsLong(),
                                 targets.replaying().getAsBoolean());
    }
}
