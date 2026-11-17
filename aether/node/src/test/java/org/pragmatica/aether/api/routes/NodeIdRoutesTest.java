// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.pragmatica.aether.api.ManagementApiResponses.ReserveNodeIdsRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ReserveNodeIdsResponse;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdHighWaterKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdHighWaterValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1715: the reserve route, through the real handler against a store that applies the leader transaction's compare-and-set. What is pinned:
/// the ids parse as `<source>-<role>-<n>`; an id is never handed out twice (across calls, across a lost answer, across a leader change, across
/// a scale-down); the advance is committed BEFORE the answer, so a crash between reserve and provision leaves a gap and never a reuse; a
/// reservation that loses the compare-and-set to a concurrent one re-reads and takes the ordinals above it.
class NodeIdRoutesTest {
    private static final String SRC = "eu";

    @Test
    void reserve_freshCluster_seedsFromTheBootstrapCountAndHandsOutParseableIds() {
        var cluster = new Cluster(3);
        var response = cluster.reserve(SRC, "core", 2).unwrap();

        assertThat(response.ids()).containsExactly("eu-core-3", "eu-core-4");
        assertThat(response.highWater()).isEqualTo(4);
        assertThat(response.ids()
                           .stream()
                           .map(id -> NodeIdReservation.ordinalOf(id, SRC, "core")))
                  .allMatch(Option::isPresent);
        assertThat(cluster.committedHighWater(SRC, "core")).isEqualTo(Option.some(4));
    }

    @Test
    void reserve_calledTwice_neverRepeatsAnId() {
        var cluster = new Cluster(3);
        var first = cluster.reserve(SRC, "core", 2).unwrap();
        var second = cluster.reserve(SRC, "core", 2).unwrap();

        assertThat(second.ids()).containsExactly("eu-core-5", "eu-core-6");
        assertThat(second.ids()).doesNotContainAnyElementsOf(first.ids());
    }

    @Test
    void reserve_afterScaleDown_neverHandsBackADepartedNodesId() {
        var cluster = new Cluster(1);

        cluster.admitted("eu-core-0", "eu-core-1", "eu-core-2");
        var response = cluster.reserve(SRC, "core", 1).unwrap();

        assertThat(response.ids()).as("config says 1 core, but eu-core-1 and eu-core-2 were admitted once").containsExactly("eu-core-3");
    }

    @Test
    void reserve_isCommittedBeforeTheAnswer_soALostAnswerLeavesAGapNeverAReuse() {
        var cluster = new Cluster(3);

        cluster.loseNextAnswerAfterCommit();
        var lost = cluster.reserve(SRC, "core", 2);

        assertThat(lost.isFailure()).as("the caller never saw the ids").isTrue();
        assertThat(cluster.committedHighWater(SRC, "core")).as("but the advance was committed first").isEqualTo(Option.some(4));

        var next = cluster.reserve(SRC, "core", 1).unwrap();

        assertThat(next.ids()).as("a gap at eu-core-3 and eu-core-4, never a reuse").containsExactly("eu-core-5");
    }

    @Test
    void reserve_afterLeaderChange_continuesFromTheCommittedHighWater() {
        var cluster = new Cluster(3);

        cluster.reserve(SRC, "core", 2).unwrap();
        var newLeader = cluster.otherNode();
        var response = newLeader.reserve(SRC, "core", 1).unwrap();

        assertThat(response.ids()).containsExactly("eu-core-5");
    }

    @Test
    void reserve_onANodeThatIsNotTheCommittedLeader_isRefusedAndWritesNothing() {
        var cluster = new Cluster(3);

        cluster.setLeader(false);
        var result = cluster.reserve(SRC, "core", 1);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        assertThat(cluster.committedHighWater(SRC, "core")).isEqualTo(Option.none());
    }

    @Test
    void reserve_losingTheCompareAndSetToAConcurrentReservation_rereadsAndTakesTheOrdinalsAboveIt() {
        var cluster = new Cluster(3);

        cluster.concurrentReservationBeforeNextCommit(SRC, "core", 2);
        var response = cluster.reserve(SRC, "core", 1).unwrap();

        assertThat(response.ids()).as("the concurrent caller holds eu-core-3 and eu-core-4").containsExactly("eu-core-5");
        assertThat(cluster.committedHighWater(SRC, "core")).isEqualTo(Option.some(5));
    }

    @Test
    void reserve_losingTheCompareAndSetEveryTime_isRefusedAndNeverAnswersWithIds() {
        var cluster = new Cluster(3);

        cluster.concurrentReservationBeforeEveryCommit(SRC, "core", 1);
        var result = cluster.reserve(SRC, "core", 1);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
        assertThat(cluster.committedHighWater(SRC, "core")).as("three attempts, each beaten by one concurrent ordinal: the retry is bounded at three")
                                                           .isEqualTo(Option.some(5));
    }

    @Test
    void reserve_withNoPersistedConfigAndNoIdentities_startsAtOrdinalZero() {
        var response = new Cluster(new TestKVStore()).reserve(SRC, "core", 2).unwrap();

        assertThat(response.ids()).containsExactly("eu-core-0", "eu-core-1");
        assertThat(response.highWater()).isEqualTo(1);
    }

    @Test
    void reserve_sourceAndRoleKeepSeparateHighWaters() {
        var cluster = new Cluster(3);

        cluster.reserve(SRC, "core", 2).unwrap();
        var worker = cluster.reserve(SRC, "worker", 1).unwrap();
        var otherSource = cluster.reserve("us", "core", 1).unwrap();

        assertThat(worker.ids()).containsExactly("eu-worker-0");
        assertThat(otherSource.ids()).containsExactly("us-core-0");
    }

    @Test
    void reserve_invalidRequests_areRefusedAs400AndWriteNothing() {
        var cluster = new Cluster(3);

        for (var request : List.of(new ReserveNodeIdsRequest("e/u", "core", 1),
                                   new ReserveNodeIdsRequest("", "core", 1),
                                   new ReserveNodeIdsRequest(null, "core", 1),
                                   new ReserveNodeIdsRequest(SRC, "co re", 1),
                                   new ReserveNodeIdsRequest(SRC, "core", 0),
                                   new ReserveNodeIdsRequest(SRC, "core", NodeIdRoutes.MAX_COUNT + 1))) {
            var result = cluster.reserve(request);

            assertThat(result.isFailure()).as(request.toString()).isTrue();
            result.onFailure(cause -> assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        assertThat(cluster.committedHighWater(SRC, "core")).isEqualTo(Option.none());
    }

    /// One consensus store shared by every node of a test cluster, as the committed KV is.
    private static final class Cluster {
        private final TestKVStore store;
        private final boolean[] leader = {true};
        private final NodeIdRoutes routes = NodeIdRoutes.nodeIdRoutes(() -> nodeWith(store(), leader));

        private Cluster(TestKVStore store) {
            this.store = store;
        }

        Cluster(int coreCount) {
            this(new TestKVStore());
            store.seed(ClusterConfigKey.CURRENT,
                       ClusterConfigValue.bootstrapSeed("prod", "1.0.0", List.of(new TopologyEntry(SRC, "core", coreCount)), coreCount, 9, "hetzner", 1));
        }

        Result<ReserveNodeIdsResponse> reserve(String source, String role, int count) {
            return reserve(new ReserveNodeIdsRequest(source, role, count));
        }

        Result<ReserveNodeIdsResponse> reserve(ReserveNodeIdsRequest request) {
            return routes.handleReserve(request).await();
        }

        /// Another node over the same committed KV: the leader after a leader change.
        Cluster otherNode() {
            return new Cluster(store);
        }

        private TestKVStore store() {
            return store;
        }

        void setLeader(boolean value) {
            leader[0] = value;
        }

        void admitted(String... ids) {
            for (var id : ids) {
                store.seed(NodeIdentityKey.nodeIdentityKey(new NodeId(id)), new NodeLifecycleValue(Option.none(), Option.none()));
            }
        }

        void loseNextAnswerAfterCommit() {
            store.loseAnswers = 1;
        }

        void concurrentReservationBeforeNextCommit(String source, String role, int count) {
            store.interference = () -> store.bump(source, role, count);
            store.interferences = 1;
        }

        void concurrentReservationBeforeEveryCommit(String source, String role, int count) {
            store.interference = () -> store.bump(source, role, count);
            store.interferences = Integer.MAX_VALUE;
        }

        Option<Integer> committedHighWater(String source, String role) {
            return store.get(NodeIdHighWaterKey.nodeIdHighWaterKey(source, role))
                        .map(NodeIdHighWaterValue.class::cast)
                        .map(NodeIdHighWaterValue::highWater);
        }
    }

    private static ManageableNode nodeWith(TestKVStore store, boolean[] leader) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, leader, method, args));
    }

    private static Object dispatch(TestKVStore store, boolean[] leader, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            case "isLeader" -> leader[0];
            case "self" -> new NodeId("core");
            case "apply" -> store.applyBatch(args);
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();
        Runnable interference = () -> {};
        int interferences;
        int loseAnswers;

        private TestKVStore() {
            super(null, null, null);
        }

        void seed(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        /// A reservation by someone else that commits between this caller's read and its commit.
        void bump(String source, String role, int count) {
            var key = NodeIdHighWaterKey.nodeIdHighWaterKey(source, role);
            var current = Option.option(storage.get(key))
                                .map(NodeIdHighWaterValue.class::cast)
                                .map(NodeIdHighWaterValue::highWater)
                                .or(source.equals(SRC) && role.equals("core") ? 2 : - 1);

            storage.put(key, new NodeIdHighWaterValue(current + count));
        }

        @SuppressWarnings("unchecked")
        Promise<List<Object>> applyBatch(Object[] args) {
            var results = ((List<KVCommand<AetherKey>>) args[0]).stream()
                                                                  .map(this::routeCommand)
                                                                  .toList();

            if (loseAnswers > 0) {
                loseAnswers--;

                return Causes.cause("the answer was lost after the commit")
                             .promise();
            }

            return Promise.success(results);
        }

        private Object routeCommand(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.LeaderTransaction<AetherKey, ?> transaction
                   ? applyTransaction(transaction)
                   : new KVCommand.TransactionResult("unexpected", false);
        }

        private KVCommand.TransactionResult applyTransaction(KVCommand.LeaderTransaction<AetherKey, ?> transaction) {
            if (interferences > 0) {
                interferences--;
                interference.run();
            }

            var mutation = transaction.mutations().getFirst();
            var accepted = get(mutation.key()).equals(mutation.expected());

            if (accepted) {
                mutation.replacement().onPresent(value -> storage.put(mutation.key(), (AetherValue) value));
            }

            return new KVCommand.TransactionResult(transaction.transactionId(), accepted);
        }

        @Override
        public <VV> Option<VV> getTyped(StructuredKey key, Class<VV> type) {
            return key == LeaderKey.INSTANCE
                   ? Option.some(type.cast(new LeaderValue(new NodeId("core"), 1)))
                   : Option.option(storage.get(key)).filter(type::isInstance).map(type::cast);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
