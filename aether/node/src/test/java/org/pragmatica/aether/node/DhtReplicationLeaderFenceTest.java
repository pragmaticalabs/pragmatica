// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2594: the replication-change commit is a leader-only write. A deposed leader still holds its local leader flag but
/// has applied the new leader's record; a transaction carrying that record passes the applier's equality fence, so the
/// settlement proposes only while the committed record names this node.
class DhtReplicationLeaderFenceTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final NodeId OTHER = new NodeId("core-2");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final ClusterConfigValue CONFIG = ClusterConfigValue.bootstrapSeed("prod",
                                                                                      "1.0.0",
                                                                                      List.of(new TopologyEntry("hetzner", "core", 3)),
                                                                                      3,
                                                                                      9,
                                                                                      "hetzner",
                                                                                      1);

    @Test
    void onConfigCommitted_committedLeaderIsAnotherNode_proposesNothing() {
        assertThat(proposalsUnder(OTHER)).as("no change carrying another node's leader record").isEmpty();
    }

    /// Control: the same commit under this node's own record proposes, so the refusal above is the fence, not the harness.
    @Test
    void onConfigCommitted_committedLeaderIsThisNode_proposes() {
        assertThat(proposalsUnder(SELF)).hasSize(1);
    }

    /// The record names this node, but its term ends after the notification's own leader check: the proposal re-reads the
    /// term at issue time and proposes nothing.
    @Test
    void onConfigCommitted_leadershipEndsBeforeTheProposal_proposesNothing() {
        var reads = new AtomicInteger();

        assertThat(proposalsUnder(SELF, () -> reads.getAndIncrement() == 0)).as("no change once this node no longer leads")
                                                                         .isEmpty();
    }

    private static List<KVCommand<AetherKey>> proposalsUnder(NodeId committedLeader) {
        return proposalsUnder(committedLeader, () -> true);
    }

    private static List<KVCommand<AetherKey>> proposalsUnder(NodeId committedLeader, Supplier<Boolean> isLeader) {
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var submitted = new ArrayList<KVCommand<AetherKey>>();

        commitLeader(kvStore, LeaderValue.leaderValue(committedLeader, 2L));
        settlement(kvStore, submitted, isLeader).onConfigCommitted(CONFIG);

        return submitted.stream()
                        .filter(KVCommand.LeaderTransaction.class::isInstance)
                        .toList();
    }

    private static DhtReplicationSettlement settlement(KVStore<AetherKey, AetherValue> kvStore,
                                                       List<KVCommand<AetherKey>> submitted,
                                                       Supplier<Boolean> isLeader) {
        var dhtNode = DHTNode.dhtNodeAwaitingReplication(SELF, memoryStorageEngine(), ring(), DHTConfig.DEFAULT, HlcClock.hlcClock(SELF));

        return DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(SELF,
                                                                                                    true,
                                                                                                    kvStore,
                                                                                                    dhtNode,
                                                                                                    DHTConfig.DEFAULT,
                                                                                                    isLeader,
                                                                                                    commands -> record(submitted, commands),
                                                                                                    Set::of,
                                                                                                    _ -> false,
                                                                                                    _ -> {},
                                                                                                    System::currentTimeMillis,
                                                                                                    () -> true,
                                                                                                    () -> true));
    }

    private static Promise<List<Object>> record(List<KVCommand<AetherKey>> submitted, List<KVCommand<AetherKey>> commands) {
        submitted.addAll(commands);

        return Promise.success(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commitLeader(KVStore<AetherKey, AetherValue> kvStore, LeaderValue leader) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, leader))));
    }

    private static ConsistentHashRing<NodeId> ring() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(SELF);

        return ring;
    }
}
