// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// The deployment FSM's writer for one leadership term (#2584). Every consensus write of `cluster.fsm` goes through
/// [#write] or one of the hierarchy writes, named by its [WriteSite].
public final class TermWriter {
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> store;
    private final NodeId self;
    private final BooleanSupplier deactivated;
    private final HierarchyStateWriter hierarchy;

    private TermWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                       KVStore<AetherKey, AetherValue> store,
                       NodeId self,
                       BooleanSupplier deactivated,
                       HierarchyStateWriter hierarchy) {
        this.cluster = cluster;
        this.store = store;
        this.self = self;
        this.deactivated = deactivated;
        this.hierarchy = hierarchy;
    }

    static TermWriter termWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> store,
                                 NodeId self,
                                 BooleanSupplier deactivated,
                                 HierarchyStateWriter hierarchy) {
        return new TermWriter(cluster, store, self, deactivated, hierarchy);
    }

    /// Builds the site's commands and applies them. The returned promise is the apply's own; the site attaches its
    /// existing success and failure handling to it.
    public <R> Promise<List<R>> write(WriteSite site, Fn0<List<KVCommand<AetherKey>>> build) {
        return cluster.apply(build.apply());
    }

    /// A conditional hierarchy put (the [WriteSite#CORE_DIRECTIVE] site). Refused with
    /// `HierarchyStateWriter.Refusal.INACTIVE` unless the committed leader record names this node and the term is live.
    public Promise<Unit> hierarchyPut(AetherKey key, Option<AetherValue> expected, AetherValue value) {
        return hierarchy.put(key, expected, value);
    }

    /// A conditional hierarchy commit (the [WriteSite#DEPARTURE_DIRECTIVE_REMOVE] site), refused as [#hierarchyPut] is.
    public Promise<Unit> hierarchyCommit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                                         List<KVCommand.ReadWitness<AetherKey>> guards) {
        return hierarchy.commit(mutations, guards);
    }
}
