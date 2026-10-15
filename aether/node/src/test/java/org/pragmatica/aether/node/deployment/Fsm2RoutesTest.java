// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// W-01 (the KV routes: the cluster set derived from `F2/Indexer` at test time, the node set), W-02 (the own-node desired filter), W-03
/// (`knownToken` at delivery), and the stamp read synchronously inside the KVStore dispatch (PR C obligation 1): driven by the **real**
/// `processCommitted` across a snapshot, never by a rig. MT-02 (each registration), MT-03, MT-04.
class Fsm2RoutesTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
    private static final Path INDEXER = Path.of("..", "aether-deployment", "src", "main", "java", "org", "pragmatica", "aether", "deployment", "cluster", "fsm2", "Indexer.java");
    private static final AetherValue HARMLESS = new AetherValue.DeploymentApplyValue("attempt", Option.none(), 1L);

    private record Delivered<I>(I input, long revision, boolean replay) {}

    /// The routes over a real store and a real notification router, with recording sinks; `delivery` commits at increasing slots.
    private static final class Rig {
        final MessageRouter.MutableRouter router = MessageRouter.mutable();
        final KVStore<AetherKey, AetherValue> store = new KVStore<>(router, new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        final BootTokens tokens = BootTokens.bootTokens(1L);
        final List<Delivered<NodeFsmInput>> node = Collections.synchronizedList(new ArrayList<>());
        final List<Delivered<ClusterInput>> cluster = Collections.synchronizedList(new ArrayList<>());
        long slot;

        Rig() {
            this(read -> read);
        }

        /// `hop` runs the stamp read; the identity is the production behaviour (the dispatching thread), a mutant moves it elsewhere.
        Rig(UnaryOperator<Supplier<Long>> hop) {
            var routes = Fsm2Routes.fsm2Routes(new Fsm2Routes.Targets(SELF,
                                                                      () -> hop.apply(store::notificationRevision).get(),
                                                                      store::isReplaying,
                                                                      tokens::tokenOf,
                                                                      (input, revision, replay) -> node.add(new Delivered<>(input, revision, replay)),
                                                                      (input, revision, replay) -> cluster.add(new Delivered<>(input, revision, replay))));
            var builder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class);

            routes.register(builder);
            builder.build().asRouteEntries().forEach(this::register);
            routes.leaderEntries().forEach(this::register);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void register(MessageRouter.Entry<?> entry) {
            entry.entries().forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void put(AetherKey key, AetherValue value) {
            var raw = (KVStore) store;

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.Put(key, value))), ++slot);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void putLeader(LeaderValue leader) {
            var raw = (KVStore) store;

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, leader))), ++slot);
        }

        /// A leader-authorized write: one `LeaderTransaction` under the committed leader, as the cluster FSM writes.
        @SuppressWarnings({"unchecked", "rawtypes"})
        void leaderPut(AetherKey key, AetherValue value) {
            var raw = (KVStore) store;
            LeaderValue leader = (LeaderValue) raw.getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.LeaderTransaction(key,
                                                                                         "tx-" + (slot + 1),
                                                                                         leader,
                                                                                         List.of(),
                                                                                         List.of(new KVCommand.Mutation(key, raw.get(key), Option.some(value)))))),
                                 ++slot);
        }

        /// A leader-authorized remove: a `LeaderTransaction` whose mutation has no replacement.
        @SuppressWarnings({"unchecked", "rawtypes"})
        void leaderRemove(AetherKey key) {
            var raw = (KVStore) store;
            LeaderValue leader = (LeaderValue) raw.getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.LeaderTransaction(key,
                                                                                         "tx-" + (slot + 1),
                                                                                         leader,
                                                                                         List.of(),
                                                                                         List.of(new KVCommand.Mutation(key, raw.get(key), Option.none()))))),
                                 ++slot);
        }

        /// A witnessed remove: the remover presents the committed value it removes.
        @SuppressWarnings({"unchecked", "rawtypes"})
        void removeWitnessed(AetherKey key) {
            var raw = (KVStore) store;

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.Remove(key, raw.get(key)))), ++slot);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void remove(AetherKey key) {
            var raw = (KVStore) store;

            raw.processCommitted(raw.createBatch(List.of(new KVCommand.Remove(key))), ++slot);
        }
    }

    private static SliceDesiredValue desired() {
        return new SliceDesiredValue(SliceGoal.ACTIVE, new Generation(1L, 1L), 1L);
    }

    private static SliceObservedValue observed(long version) {
        return new SliceObservedValue(SliceObservedState.ACTIVE, version, new Generation(1L, 1L), new ProcessIncarnation(1L, 5L), Option.none(), false, 0, List.of(), 1L);
    }

    // -- W-01: the cluster set, derived from the Indexer ----------------------------------------------------------------------
    /// One sample key per key family the Indexer names. A family the Indexer learns without a sample here fails by name, loudly.
    private static Map<String, AetherKey> samples() {
        return Map.ofEntries(Map.entry("AppBlueprintKey", AetherKey.AppBlueprintKey.appBlueprintKey(BLUEPRINT)),
                             Map.entry("SliceTargetKey", AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base())),
                             Map.entry("VersionRoutingKey", AetherKey.VersionRoutingKey.versionRoutingKey(ORDERS.base())),
                             Map.entry("SliceDesiredKey", SliceDesiredKey.sliceDesiredKey(PEER, ORDERS)),
                             Map.entry("SliceObservedKey", SliceObservedKey.sliceObservedKey(PEER, ORDERS)),
                             Map.entry("ActivationDirectiveKey", AetherKey.ActivationDirectiveKey.activationDirectiveKey(PEER)),
                             Map.entry("CommunityKey", AetherKey.CommunityKey.communityKey("c1")),
                             Map.entry("GovernorAnnouncementKey", AetherKey.GovernorAnnouncementKey.forCommunity("c1")),
                             Map.entry("NodeDepartureKey", AetherKey.NodeDepartureKey.nodeDepartureKey(PEER)),
                             Map.entry("ArtifactJudgmentKey", AetherKey.ArtifactJudgmentKey.artifactJudgmentKey(ORDERS)),
                             Map.entry("DeploymentApplyKey", AetherKey.DeploymentApplyKey.deploymentApplyKey(BLUEPRINT)),
                             Map.entry("DeploymentOutcomeKey", AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT)),
                             Map.entry("SchemaVersionKey", AetherKey.SchemaVersionKey.schemaVersionKey("orders-db")),
                             Map.entry("SchemaMigrationLockKey", AetherKey.SchemaMigrationLockKey.schemaMigrationLockKey("orders-db")),
                             Map.entry("NodeRoutesKey", AetherKey.NodeRoutesKey.nodeRoutesKey(PEER, ORDERS)),
                             Map.entry("NodePlacementKey", new AetherKey.NodePlacementKey(PEER)),
                             Map.entry("CommunityPlacementOperationKey", new AetherKey.CommunityPlacementOperationKey("c1")),
                             Map.entry("ClusterConfigKey", AetherKey.ClusterConfigKey.clusterConfigKey(1L)));
    }

    /// The families `Indexer.indexPut` / `indexRemove` switch on, read from the Indexer's own source.
    private static List<String> indexerFamilies(String method, String until) {
        try {
            var text = Files.readString(INDEXER);
            var from = text.indexOf("static ClusterModel " + method + "(");
            var to = text.indexOf(until, from);
            var section = text.substring(from, to);

            return Pattern.compile("case (?:AetherKey\\.)?(\\w+Key)\\b").matcher(section).results().map(match -> match.group(1)).collect(Collectors.toCollection(TreeSet::new)).stream().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    class ClusterFamilies {
        @Test
        void everyFamilyTheIndexerReads_yieldsExactlyOneInputPerCommittedPut_andPerCommittedRemove() {
            var puts = indexerFamilies("indexPut", "static ClusterModel indexRemove(");
            var removes = indexerFamilies("indexRemove", "// -- blueprints, targets, routings");
            var samples = samples();

            assertThat(puts).as("the scan read the Indexer's put switch").hasSizeGreaterThanOrEqualTo(18);
            assertThat(removes).as("and its remove switch").hasSizeGreaterThanOrEqualTo(17);
            assertThat(samples.keySet()).as("a sample key exists for every family the Indexer names").containsAll(puts);
            var rig = new Rig();

            puts.forEach(family -> {
                var before = rig.cluster.size();

                rig.put(samples.get(family), HARMLESS);
                assertThat(rig.cluster).as("a committed put of " + family).hasSize(before + 1);
                assertThat(rig.cluster.getLast().input()).isEqualTo(new ClusterInput.KvPut(samples.get(family), HARMLESS));
            });
            removes.forEach(family -> {
                var before = rig.cluster.size();

                rig.remove(samples.get(family));
                assertThat(rig.cluster).as("a committed remove of " + family).hasSize(before + 1);
                assertThat(rig.cluster.getLast().input()).isEqualTo(new ClusterInput.KvRemove(samples.get(family)));
            });
            assertThat(Fsm2Routes.CLUSTER_PUT_FAMILIES.stream().map(Class::getSimpleName)).as("the routes are exactly the Indexer's set").containsExactlyInAnyOrderElementsOf(puts);
            assertThat(Fsm2Routes.CLUSTER_REMOVE_FAMILIES.stream().map(Class::getSimpleName)).containsExactlyInAnyOrderElementsOf(removes);
        }

        /// The positive control: a key the FSMs do not read yields nothing, so a route that fired for everything could not pass.
        @Test
        void aKeyNoFsmReads_yieldsNothing() {
            var rig = new Rig();

            rig.put(new AetherKey.AlertThresholdKey("cpu"), HARMLESS);
            rig.remove(new AetherKey.AlertThresholdKey("cpu"));

            assertThat(rig.cluster).isEmpty();
            assertThat(rig.node).isEmpty();
        }

        /// B8: the committed `LeaderValue` is the only source of `viewSequence`.
        @Test
        void aCommittedLeader_isDeliveredAsLeaderObserved() {
            var rig = new Rig();
            var leader = new LeaderValue(SELF, 9L);

            rig.putLeader(leader);

            assertThat(rig.cluster).extracting(Delivered::input).containsExactly(new ClusterInput.LeaderObserved(leader));
        }
    }

    // -- W-02, W-03 ------------------------------------------------------------------------------------------------------------
    @Nested
    class NodeFamilies {
        /// W-02 / MT-03: another node's goal never reaches this node's FSM; this node's own does, put and remove.
        @Test
        void desiredRows_reachTheNodeFsmForThisNodeOnly() {
            var rig = new Rig();

            rig.putLeader(new LeaderValue(SELF, 1L));
            rig.cluster.clear();
            rig.leaderPut(SliceDesiredKey.sliceDesiredKey(PEER, ORDERS), desired());
            rig.leaderRemove(SliceDesiredKey.sliceDesiredKey(PEER, ORDERS));
            assertThat(rig.node).as("another node's goal").isEmpty();

            rig.leaderPut(SliceDesiredKey.sliceDesiredKey(SELF, ORDERS), desired());
            rig.leaderRemove(SliceDesiredKey.sliceDesiredKey(SELF, ORDERS));

            assertThat(rig.node).extracting(Delivered::input).containsExactly(new NodeFsmInput.DesiredPut(ORDERS, desired()), new NodeFsmInput.DesiredRemove(ORDERS));
        }

        /// W-03 / MT-04: the boot token is the registry's for the writer AT DELIVERY. A later admission does not rewrite an input already
        /// delivered, and the token of this node is not substituted for the writer's.
        @Test
        void observedRows_carryTheWritersTokenAtDelivery_notLaterOneAndNotSelfs() {
            var rig = new Rig();

            rig.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), observed(1L));
            rig.tokens.admit(PEER, 77L);
            rig.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), observed(2L));
            rig.put(SliceObservedKey.sliceObservedKey(SELF, ORDERS), observed(1L));
            rig.removeWitnessed(SliceObservedKey.sliceObservedKey(PEER, ORDERS));

            assertThat(rig.node).extracting(Delivered::input)
                                .containsExactly(new NodeFsmInput.ObservedPut(PEER, ORDERS, observed(1L), 0L),
                                                 new NodeFsmInput.ObservedPut(PEER, ORDERS, observed(2L), 77L),
                                                 new NodeFsmInput.ObservedPut(SELF, ORDERS, observed(1L), 0L),
                                                 new NodeFsmInput.ObservedRemove(PEER, ORDERS));
        }

        @Test
        void targetsAndRoutings_reachTheNodeFsm_putAndRemove() {
            var rig = new Rig();
            var version = ORDERS.version();
            var other = Version.version("2.0.0").unwrap();

            rig.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(version, 3, 1));
            rig.remove(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()));
            rig.put(AetherKey.VersionRoutingKey.versionRoutingKey(ORDERS.base()), VersionRoutingValue.versionRoutingValue(version, other));
            rig.remove(AetherKey.VersionRoutingKey.versionRoutingKey(ORDERS.base()));

            assertThat(rig.node).extracting(Delivered::input)
                                .containsExactly(new NodeFsmInput.TargetPut(ORDERS.base(), version),
                                                 new NodeFsmInput.TargetRemove(ORDERS.base()),
                                                 new NodeFsmInput.RoutingPut(ORDERS.base(), version, other),
                                                 new NodeFsmInput.RoutingRemove(ORDERS.base()));
        }
    }

    // -- obligation 1: the stamp is read synchronously, inside the dispatch --------------------------------------------------
    @Nested
    class SynchronousStamp {
        /// Every notification of a batch carries the batch's slot, read on the dispatching thread.
        @Test
        void everyNotification_carriesThe_slotOfItsBatch_readInsideTheDispatch() {
            var rig = new Rig();

            rig.put(SliceObservedKey.sliceObservedKey(SELF, ORDERS), observed(1L));
            rig.put(SliceObservedKey.sliceObservedKey(SELF, ORDERS), observed(2L));
            rig.put(SliceObservedKey.sliceObservedKey(SELF, ORDERS), observed(3L));

            assertThat(rig.node).extracting(Delivered::revision).containsExactly(1L, 2L, 3L);
            assertThat(rig.node).extracting(Delivered::replay).containsOnly(false);
        }

        /// MT: a handler that moves the stamp read to another thread reads the dispatch-scoped stamp outside the dispatch, which is `0`. The
        /// positive control of the test below: it shows the zero a hop would produce.
        @Test
        void aHop_readsZero_outsideTheDispatch() {
            var rig = new Rig(read -> () -> hopped(read));

            rig.put(SliceObservedKey.sliceObservedKey(SELF, ORDERS), observed(1L));

            assertThat(rig.node).extracting(Delivered::revision).containsExactly(0L);
        }

        private Long hopped(Supplier<Long> read) {
            var holder = new Long[1];
            var thread = Thread.ofPlatform().start(() -> holder[0] = read.get());

            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return holder[0];
        }
    }
}
