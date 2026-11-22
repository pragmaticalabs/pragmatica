// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;

import static org.junit.jupiter.api.Assertions.fail;

/// #2767: plays, for a single-node boot test, the leader a lone node never elects, so an FSM2 node can leave JOINING.
///
/// An FSM2 node enters (QuorumEstablished, then SelfReady) only once its `EngineMarkerGuard` reads a committed FSM2 marker, and the
/// marker is minted with the cluster incarnation by the elected leader's genesis leg (`ClusterIncarnationRegistrar.genesisLeg`,
/// armed on `LeaderChange`, witnessed by the committed `LeaderValue`). A lone node never elects itself and commits nothing through
/// consensus, and a lone node is not a supported topology (`Main` refuses fewer than three, #782), so a single-node boot test gets
/// neither. This commits the same two writes the real leader makes, through the node's own store: the `LeaderValue` naming the node,
/// then the genesis transaction `ClusterIncarnation.genesisCommand` builds (the incarnation and the FSM2 marker, one leader transaction).
public sealed interface LoneNodeGenesis {
    long VIEW_SEQUENCE = 1L;

    /// Commits the leader and the genesis of an FSM2 lineage into the booted node's store, and waits for their delivery.
    static void mintAsTheLeader(AetherNode node) {
        var store = node.kvStore();

        commit(store, new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(node.self(), VIEW_SEQUENCE)));
        ClusterIncarnation.genesisCommand(store,
                                          ClusterIncarnation.Mint.mint(DeploymentEngine.FSM2, node.self()))
                          .onFailure(cause -> fail("the genesis command must build over the committed leader: " + cause.message()))
                          .onSuccess(command -> command.onPresent(minted -> commit(store, minted)));
        KvDelivery.awaitDelivered(store);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commit(KVStore<AetherKey, AetherValue> store, KVCommand command) {
        var raw = (KVStore) store;

        raw.process(raw.createBatch(List.of(command)));
    }

    record unused() implements LoneNodeGenesis {}
}
