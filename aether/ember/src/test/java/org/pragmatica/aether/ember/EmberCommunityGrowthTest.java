// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #241: the growth policy for derived communities, wired through a real cluster. The unit tests prove the policy and the placement pass on
/// fakes; this proves the wiring: a `[community_policy]` committed in the cluster config reaches the leader's deployment state, a worker that
/// finds its source's community full opens the next one, and the placement pass dissolves a derived community that has stayed empty.
///
/// Five cores and four workers of one source, `max_size = 2`: two workers fill `eu-w-0` and the other two open and fill `eu-w-1`, with
/// no `default-w-0` (the source label round-trips) and no worker promoted to core. Then the two members of `eu-w-1` lose their directives, as
/// they do when their termination is confirmed, and after the grace the leader's pass writes DISSOLVING and then DISSOLVED. The viability
/// floor is 1, so a community of one member is viable: ACTIVE is not asserted (two ACTIVE communities at the replication factor need more
/// nodes than an in-JVM cluster survives, #336).
@PortBudget
class EmberCommunityGrowthTest {
    private static final int CORES = 5;
    private static final int WORKERS = 4;
    private static final int SLOTS = CORES + WORKERS + 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long WAIT_BOUND_MS = 150_000L;
    private static final String SOURCE = "eu";
    private static final String FIRST = SOURCE + "-w-0";
    private static final String SECOND = SOURCE + "-w-1";
    private static final String POLICY = """
        config_version = "1.0.0"
        [cluster]
        name = "growth"
        version = "1.0.0"
        [source.eu]
        type = "forge"
        [source.eu.worker]
        count = 4
        [community_policy]
        max_size = 2
        viability_floor = 1
        allow_below_rf = true
        max_communities = 5
        dissolve_grace_seconds = 3
        growth = true
        """;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(900)
    void workersFillACommunityThenOpenTheNext_andAnEmptyOneIsDissolved() {
        cluster = EmberTestPorts.startedCluster(PORTS, candidate -> {
            var started = emberCluster(CORES, candidate, candidate + MGMT_OFFSET, candidate + APP_OFFSET, "cgr");

            started.withRaisedSwimTimeouts();
            return started;
        }, START_BOUND);
        await("a leader is elected and the core cap is committed", () -> cluster.currentLeader().isPresent() && committedConfig().isPresent());
        commitPolicy();

        var workers = new java.util.ArrayList<NodeId>();

        for (int index = 1; index <= WORKERS; index++) {
            workers.add(joinWorker(index));
        }

        await("all four workers hold a community directive", () -> workers.stream().allMatch(worker -> communityOf(worker).isPresent()));
        var assigned = workers.stream().collect(java.util.stream.Collectors.groupingBy(worker -> communityOf(worker).or(""), java.util.stream.Collectors.counting()));

        // The joins reach the leader together and in any order, so which worker lands where is not pinned, only the shape of the result.
        assertThat(assigned).as("two workers fill the first community, two open and fill the second: %s", assigned).isEqualTo(Map.of(FIRST, 2L, SECOND, 2L));
        assertThat(community(FIRST).map(CommunityValue::targetSize).or(-1)).as("the committed max_size is stamped").isEqualTo(2);
        assertThat(community(SECOND).map(CommunityValue::sourceName).or("")).isEqualTo(SOURCE);
        assertThat(community("default-w-0").isPresent()).as("the source label round-trips: no worker fell back to the default source").isFalse();
        assertThat(leader().membershipFsm().coreCountedMembers()).as("no worker was promoted to core").hasSize(CORES);

        workers.stream().filter(worker -> communityOf(worker).or("").equals(SECOND)).forEach(this::dropDirective);

        await("the empty second community is dissolved after the grace",
              () -> community(SECOND).filter(value -> value.state() == CommunityState.DISSOLVED).isPresent());
        assertThat(community(SECOND).map(CommunityValue::targetSize).or(-1)).isZero();
        assertThat(community(FIRST).map(CommunityValue::state).or(CommunityState.UNKNOWN)).as("the populated community is untouched")
            .isNotIn(CommunityState.DISSOLVING, CommunityState.DISSOLVED);
    }

    private NodeId joinWorker(int index) {
        var added = cluster.addNode(Map.of(NodeInfo.LABEL_ROLE, "worker", NodeInfo.LABEL_SOURCE, SOURCE)).await(START_BOUND);

        assertThat(added.isSuccess()).as("worker %d starts", index).isTrue();
        var worker = new NodeId(added.unwrap().id());

        await("worker " + index + " settled: leader present and " + (CORES + index) + " nodes",
              () -> cluster.currentLeader().isPresent() && cluster.nodeCount() == CORES + index);
        return worker;
    }

    /// Writes the cluster config again as the leader does, with the policy in its TOML and every other field as it was.
    private void commitPolicy() {
        var node = leader();
        var before = committedConfig().unwrap();
        var value = new ClusterConfigValue(Option.some(POLICY),
                                           before.clusterName(),
                                           before.version(),
                                           before.desiredTopology(),
                                           before.coreMin(),
                                           before.coreMax(),
                                           before.deploymentType(),
                                           before.configVersion() + 1,
                                           System.currentTimeMillis());

        commit(node, ClusterConfigKey.CURRENT, Option.some(before), Option.some(value));
    }

    /// The directive of a node whose termination is confirmed is removed by the leader, in a leader transaction.
    private void dropDirective(NodeId worker) {
        var key = new ActivationDirectiveKey(worker);
        var before = leader().kvStore().get(key);

        assertThat(before.isPresent()).as("the worker has a directive to drop").isTrue();
        commit(leader(), key, before, Option.none());
    }

    private void commit(AetherNode node, AetherKey key, Option<? extends AetherValue> expected, Option<? extends AetherValue> replacement) {
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                  id,
                                                                                  authority,
                                                                                  List.of(),
                                                                                  List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                                         expected.map(value -> (AetherValue) value),
                                                                                                                                         replacement.map(value -> (AetherValue) value))));
        var results = node.<Object> apply(List.of(transaction)).await(APPLY_BOUND).unwrap();

        assertThat(results).as("the leader transaction on %s is accepted", key.asString())
                           .anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted
                                                && accepted.transactionId().equals(id)
                                                && accepted.accepted());
    }

    private Option<ClusterConfigValue> committedConfig() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .flatMap(node -> node.kvStore().getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class));
    }

    private Option<CommunityValue> community(String id) {
        return leader().kvStore().getTyped(CommunityKey.communityKey(id), CommunityValue.class);
    }

    private Option<String> communityOf(NodeId worker) {
        return leader().kvStore()
                       .getTyped(new ActivationDirectiveKey(worker), ActivationDirectiveValue.class)
                       .map(ActivationDirectiveValue::communityId)
                       .filter(id -> !id.isBlank());
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }

    private static void await(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + WAIT_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
