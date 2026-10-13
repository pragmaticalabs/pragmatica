// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.ACTIVATING;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.ACTIVE;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.DEACTIVATING;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.FAILED;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.LOADED;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.LOADING;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.UNLOADED;
import static org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState.UNLOADING;

/// The N table of spec R-3.4.3 as an oracle written from the spec, not from the code: for each committed state of the node's
/// own row, the states a node write may take it to. It is deliberately independent of `Plans`. Any state may go to FAILED
/// (N3, N5, N6, N14, N19); the absent row is the first write of a chain. There is no ROUTING (Q4). ACTIVATING to LOADING is
/// the one edge the spec does not draw: a goal of LOADED over an ACTIVATING row whose slice left the store reloads it instead of
/// claiming LOADED [guess]. FAILED to FAILED is a new attempt from a FAILED row whose first write was lost.
final class NodeTransitionTable {
    private static final Map<SliceObservedState, Set<SliceObservedState>> FROM = Map.of(LOADING,
                                                                                         EnumSet.of(LOADED, UNLOADING, FAILED),
                                                                                         LOADED,
                                                                                         EnumSet.of(ACTIVATING, LOADING, UNLOADING, FAILED),
                                                                                         ACTIVATING,
                                                                                         EnumSet.of(ACTIVE, DEACTIVATING, LOADING, UNLOADING, FAILED),
                                                                                         ACTIVE,
                                                                                         EnumSet.of(DEACTIVATING, LOADED, UNLOADING, LOADING, ACTIVE, FAILED),
                                                                                         DEACTIVATING,
                                                                                         EnumSet.of(LOADED, ACTIVATING, LOADING, UNLOADING, FAILED),
                                                                                         FAILED,
                                                                                         EnumSet.of(UNLOADING, LOADING, FAILED),
                                                                                         UNLOADING,
                                                                                         EnumSet.of(UNLOADED, LOADING, FAILED),
                                                                                         UNLOADED,
                                                                                         EnumSet.of(LOADING, FAILED));
    private static final Set<SliceObservedState> FIRST_WRITE = EnumSet.of(LOADING, UNLOADING, FAILED);

    private NodeTransitionTable() {}

    static boolean allows(Option<SliceObservedState> committed, SliceObservedState next) {
        return committed.fold(() -> FIRST_WRITE.contains(next), state -> FROM.getOrDefault(state, Set.of()).contains(next));
    }
}
