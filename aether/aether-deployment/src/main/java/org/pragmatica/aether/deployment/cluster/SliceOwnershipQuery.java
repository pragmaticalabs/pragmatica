// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Active-slice-ownership query over the authoritative KV-Store. Produces the narrow
/// [`Predicate`] consulted by [`org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler`]
/// during drain-victim selection so a node currently serving / hosting active slices is drained as
/// scale-down or over-provision surplus only after every eligible non-owner (the 7→5-scale-down-
/// under-load incident; #1488 made ownership a demotion, not an exclusion), and — through
/// [`#drainKeepsMinAvailable`] — never when draining it would drop a hosted slice below its
/// `minAvailable`.
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

    /// Build the drain-availability guard backed by `kvStore` (#1488 owner ruling): `true` when
    /// draining `candidate` — on top of the victims `alreadySelected` earlier in the same pass —
    /// leaves every slice `candidate` hosts with at least its `minAvailable` ACTIVE instances on the
    /// nodes that remain. `minAvailable` is the slice target's [`SliceTargetValue#effectiveMinInstances`]
    /// (the blueprint `minAvailable`, default `ceil(instances/2)`, clamped to at least 1; a slice with
    /// no target counts as 1). Instances are counted per exact artifact version and only in state
    /// ACTIVE, so an instance still loading never counts toward what remains — the conservative side:
    /// a guard that under-counts defers a drain, one that over-counts takes a slice dark. Counting the
    /// already-selected victims as departing is what stops a multi-victim pass from taking two of a
    /// slice's three instances. Reads the KV-Store fresh on each call, like [`#ownsActiveSlices`].
    static BiPredicate<NodeId, Set<NodeId>> drainKeepsMinAvailable(KVStore<AetherKey, AetherValue> kvStore) {
        return (candidate, alreadySelected) -> keepsMinAvailable(kvStore, candidate, alreadySelected);
    }

    private static boolean keepsMinAvailable(KVStore<AetherKey, AetherValue> kvStore,
                                             NodeId candidate,
                                             Set<NodeId> alreadySelected) {
        var placements = livePlacements(kvStore);
        var departing = departingNodes(candidate, alreadySelected);

        return hostedArtifacts(placements, candidate).stream()
                              .allMatch(artifact -> keepsSliceMinAvailable(kvStore, placements, artifact, departing));
    }

    /// The distinct artifacts `node` holds a LIVE placement of.
    private static Set<Artifact> hostedArtifacts(Map<NodeArtifactKey, SliceState> placements, NodeId node) {
        return placements.keySet()
                         .stream()
                         .filter(key -> key.isForNode(node))
                         .map(NodeArtifactKey::artifact)
                         .collect(Collectors.toSet());
    }

    private static boolean keepsSliceMinAvailable(KVStore<AetherKey, AetherValue> kvStore,
                                                  Map<NodeArtifactKey, SliceState> placements,
                                                  Artifact artifact,
                                                  Set<NodeId> departing) {
        return remainingActive(placements, artifact, departing) >= minAvailable(kvStore, artifact);
    }

    private static Set<NodeId> departingNodes(NodeId candidate, Set<NodeId> alreadySelected) {
        var departing = new HashSet<>(alreadySelected);

        departing.add(candidate);

        return departing;
    }

    /// Every LIVE placement in the KV-Store, keyed by `(node, artifact)`, with its state.
    private static Map<NodeArtifactKey, SliceState> livePlacements(KVStore<AetherKey, AetherValue> kvStore) {
        var placements = new HashMap<NodeArtifactKey, SliceState>();

        kvStore.forEach(NodeArtifactKey.class,
                        NodeArtifactValue.class,
                        (key, value) -> recordIfLive(placements, key, value));

        return placements;
    }

    @Contract
    private static void recordIfLive(Map<NodeArtifactKey, SliceState> placements,
                                     NodeArtifactKey key,
                                     NodeArtifactValue value) {
        if (isLiveState(value.state())) {
            placements.put(key, value.state());
        }
    }

    private static long remainingActive(Map<NodeArtifactKey, SliceState> placements,
                                        Artifact artifact,
                                        Set<NodeId> departing) {
        return placements.entrySet()
                         .stream()
                         .filter(placement -> isRemainingActiveInstance(placement, artifact, departing))
                         .count();
    }

    /// An ACTIVE instance of `artifact` on a node that is not departing in this pass.
    private static boolean isRemainingActiveInstance(Map.Entry<NodeArtifactKey, SliceState> placement,
                                                     Artifact artifact,
                                                     Set<NodeId> departing) {
        var key = placement.getKey();

        return key.artifact()
                  .equals(artifact)
               && !departing.contains(key.nodeId())
               && placement.getValue() == SliceState.ACTIVE;
    }

    private static int minAvailable(KVStore<AetherKey, AetherValue> kvStore, Artifact artifact) {
        return kvStore.get(SliceTargetKey.sliceTargetKey(artifact.base()))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast)
                      .map(SliceTargetValue::effectiveMinInstances)
                      .or(1);
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
