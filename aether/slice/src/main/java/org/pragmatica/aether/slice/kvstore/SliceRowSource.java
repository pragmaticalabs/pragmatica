// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.function.ToLongFunction;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

/// Where a reader that scans the store finds the per-node slice rows (deployment state machine spec 1.6.1), chosen by the deployment engine.
///
/// Under LEGACY the rows are the `NodeArtifactKey` rows, unchanged. Under FSM2 they are the **current** `SliceObservedKey` rows: a row stale on
/// positive evidence (both boot tokens known and different, by `tokenOf` at scan time) and the UNLOADED tombstone read as absent, and an
/// unknown token reads current (R-1.6.2, C8). The engine writes only its own family, so a source of the other engine finds nothing.
@FunctionalInterface
public interface SliceRowSource {
    /// One row: the node, the artifact and the state in the legacy vocabulary ([ObservedSlices#sliceState]).
    @FunctionalInterface
    interface RowConsumer {
        @Contract
        void accept(NodeId node, Artifact artifact, SliceState state);
    }

    /// Hands every current row of `store` to `consumer`.
    @Contract
    void forEachRow(KVStore<AetherKey, AetherValue> store, RowConsumer consumer);

    /// The LEGACY rows: every `NodeArtifactKey` row as committed.
    static SliceRowSource legacy() {
        return (store, consumer) -> store.forEach(NodeArtifactKey.class,
                                                  NodeArtifactValue.class,
                                                  (key, value) -> consumer.accept(key.nodeId(), key.artifact(), value.state()));
    }

    /// The FSM2 rows: every current `SliceObservedKey` row, with each node's token read from `tokenOf` (`0` while unknown).
    static SliceRowSource observed(ToLongFunction<NodeId> tokenOf) {
        return (store, consumer) -> store.forEach(SliceObservedKey.class,
                                                  SliceObservedValue.class,
                                                  (key, row) -> acceptCurrent(consumer, tokenOf, key, row));
    }

    private static void acceptCurrent(RowConsumer consumer, ToLongFunction<NodeId> tokenOf, SliceObservedKey key, SliceObservedValue row) {
        ObservedSlices.currentState(row, tokenOf.applyAsLong(key.nodeId()))
                      .onPresent(state -> consumer.accept(key.nodeId(), key.artifact(), state));
    }
}
