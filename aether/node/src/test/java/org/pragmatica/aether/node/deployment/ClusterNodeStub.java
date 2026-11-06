// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// A `ClusterNode` whose `apply` is scripted and recorded.
final class ClusterNodeStub implements ClusterNode<KVCommand<AetherKey>> {
    private final NodeId self;
    final List<List<KVCommand<AetherKey>>> applied = Collections.synchronizedList(new ArrayList<>());
    volatile Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> script = _ -> Promise.success(List.of());

    ClusterNodeStub(NodeId self) {
        this.self = self;
    }

    @Override
    public NodeId self() {
        return self;
    }

    @Override
    public TopologyManager topologyManager() {
        throw new UnsupportedOperationException("not used by the wiring tests");
    }

    @Override
    public Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
        applied.add(List.copyOf(commands));

        return (Promise<List<R>>)(Promise<?>) script.apply(commands);
    }
}
