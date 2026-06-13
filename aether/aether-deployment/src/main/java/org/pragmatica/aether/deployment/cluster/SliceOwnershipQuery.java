// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;


/// Active-slice-ownership query over the authoritative KV-Store. Produces the narrow
/// [`Predicate`] consulted by [`org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler`]
/// during drain-victim selection so a node currently serving / hosting active slices is never
/// drained as scale-down or over-provision surplus (the 7→5-scale-down-under-load incident).
///
/// Ownership is read from the KV-Store entries the cluster-deployment FSM itself uses as its source
/// of truth: `NodeArtifactKey(nodeId, artifact) → NodeArtifactValue(state, ...)`. A node OWNS an
/// active slice when it has at least one entry whose [`SliceState`] is LIVE — anything except the
/// terminal/teardown states FAILED / UNLOAD / UNLOADING (mirrors the FSM's `isLiveState`). A LIVE
/// state therefore includes the load/activate ramp (LOAD…ACTIVE), so a node mid-role-propagation
/// that is ABOUT to own slices is shielded too, satisfying the "do not drain a node about to own
/// slices" caveat without any extra signal.
///
/// Reading from the KV-Store keeps the predicate leader-agnostic and reconstructible from cluster
/// state (it works on any node, not just the one holding the in-memory deployment `Active` state),
/// and keeps the membership layer free of any hard dependency on the deployment FSM — the
/// reconciler sees only a `Predicate<NodeId>`.
public sealed interface SliceOwnershipQuery {
    /// Build the active-slice-ownership predicate backed by `kvStore`. The returned predicate reads
    /// the KV-Store fresh on each call (never a stale snapshot), so it always reflects the latest
    /// committed slice placement at the moment a drain pass evaluates a candidate.
    static Predicate<NodeId> ownsActiveSlices(KVStore<AetherKey, AetherValue> kvStore) {
        return nodeId -> nodeHasLiveSlice(kvStore, nodeId);
    }

    private static boolean nodeHasLiveSlice(KVStore<AetherKey, AetherValue> kvStore, NodeId nodeId) {
        var found = new AtomicBoolean(false);

        kvStore.forEach(NodeArtifactKey.class,
                        NodeArtifactValue.class,
                        (key, value) -> recordIfLiveOwnership(found, nodeId, key, value));

        return found.get();
    }

    private static void recordIfLiveOwnership(AtomicBoolean found,
                                              NodeId nodeId,
                                              NodeArtifactKey key,
                                              NodeArtifactValue value) {
        if (key.nodeId().equals(nodeId) && isLiveState(value.state())) {
            found.set(true);
        }
    }

    /// A slice state in which the node is actively hosting (or ramping toward hosting) the slice —
    /// everything except the terminal/teardown states. Mirrors the cluster-deployment FSM's own
    /// `isLiveState` so the drain guard and the FSM agree on what "owns a slice" means.
    private static boolean isLiveState(SliceState state) {
        return state != SliceState.FAILED
               && state != SliceState.UNLOAD
               && state != SliceState.UNLOADING;
    }

    record unused() implements SliceOwnershipQuery {}
}
