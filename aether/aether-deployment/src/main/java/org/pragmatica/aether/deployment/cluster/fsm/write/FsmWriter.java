// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import java.util.function.BooleanSupplier;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The only holder of the consensus [ClusterNode] in the leader's deployment FSM (#2584). Every write the FSM issues
/// goes through a [TermWriter] opened from it, so nothing else in `cluster.fsm` can reach the applier.
public final class FsmWriter {
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> store;
    private final NodeId self;
    private final HierarchyStateWriter hierarchy;

    private FsmWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                      KVStore<AetherKey, AetherValue> store,
                      NodeId self,
                      HierarchyStateWriter hierarchy) {
        this.cluster = cluster;
        this.store = store;
        this.self = self;
        this.hierarchy = hierarchy;
    }

    public static FsmWriter fsmWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                                      KVStore<AetherKey, AetherValue> store,
                                      NodeId self) {
        return new FsmWriter(cluster,
                             store,
                             self,
                             HierarchyStateWriter.hierarchyStateWriter(() -> leaderNaming(store, self),
                                                                       store::get,
                                                                       commands -> cluster.apply(commands)));
    }

    /// The writer of one leadership term. `deactivated` is the term's own flag: once it reads true the term has
    /// ended, and the writer issues nothing more.
    public TermWriter openTerm(BooleanSupplier deactivated) {
        return TermWriter.termWriter(cluster,
                                     store,
                                     self,
                                     deactivated,
                                     hierarchy.whileActive(() -> !deactivated.getAsBoolean()));
    }

    /// The committed leader record, when it names `self`: the hierarchy writer's authority.
    private static Option<LeaderValue> leaderNaming(KVStore<AetherKey, AetherValue> store, NodeId self) {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .filter(value -> value.leader()
                                          .equals(self));
    }
}
