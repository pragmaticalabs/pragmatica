// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// A cluster node whose every submitted batch commits at once into the real store (the applier's results come back as the promise's
/// value), counting the batches it was given.
final class ImmediateClusterNode implements ClusterNode<KVCommand<AetherKey>> {
    private final NodeId self;
    private final KVStore<AetherKey, AetherValue> store;
    private int submitted;

    ImmediateClusterNode(NodeId self, KVStore<AetherKey, AetherValue> store) {
        this.self = self;
        this.store = store;
    }

    synchronized int submitted() {
        return submitted;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
        submitted++;

        return Promise.success((List<R>) store.<Object> process(store.createBatch(batch)));
    }

    @Override public NodeId self() {return self;}

    @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("unused");}

    @Override public Promise<Unit> start() {return Promise.unitPromise();}

    @Override public Promise<Unit> stop() {return Promise.unitPromise();}
}
