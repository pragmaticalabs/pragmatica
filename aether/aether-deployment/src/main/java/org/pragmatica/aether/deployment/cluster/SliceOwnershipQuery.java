// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
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
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Active-slice-ownership query over the authoritative KV-Store. Produces the narrow
/// [`Predicate`] consulted by [`org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler`]
/// during drain-victim selection so a node currently serving / hosting active slices is drained as
/// scale-down or over-provision surplus only after every eligible non-owner (the 7→5-scale-down-
/// under-load incident; #1488 made ownership a demotion, not an exclusion), and — through
/// [`#minAvailableDrainGuard`] — never when draining it would drop a hosted slice below its
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

    /// Build the drain-availability guard backed by `kvStore` (#1488 owner ruling). Applied to
    /// `(candidate, remainingNodes)` it answers the first slice `candidate` hosts that would fall below
    /// its `minAvailable` ACTIVE instances if `candidate` were drained, or `none()` when every hosted
    /// slice keeps its `minAvailable`. Only placements on `remainingNodes` count — the caller passes the
    /// nodes that will still be serving: live members that are not already departing, minus the victims
    /// chosen earlier in the same pass and minus `candidate` itself. A placement on any other node is
    /// ignored, because a drained node's `NodeArtifact` entries outlive it: they are removed only once
    /// its lifecycle reaches DECOMMISSIONED, so until then a departed or departing node's ACTIVE entry
    /// would read as capacity it no longer provides.
    ///
    /// `minAvailable` is the slice target's [`SliceTargetValue#effectiveMinInstances`] (the blueprint
    /// `minAvailable`, default `ceil(instances/2)`, clamped to at least 1; a slice with no target counts
    /// as 1). Only ACTIVE instances count, so an instance still loading never counts toward what
    /// remains — the conservative side: a guard that under-counts defers a drain, one that over-counts
    /// takes a slice dark. Reads the KV-Store fresh on each call, like [`#ownsActiveSlices`].
    ///
    /// Two consequences of that conservative side are known and deliberate for now:
    /// - Instances are counted per exact artifact VERSION, while `minAvailable` is keyed by the
    ///   artifact BASE. During a rolling update the old and the new version are each compared with the
    ///   full `minAvailable`, so an owner can be refused even when both versions together would keep
    ///   the slice available. The owner has not ruled on counting per base.
    /// - A slice whose `minAvailable` equals its instance count (written today by CLI/REST deploy,
    ///   `addSliceTargetCommand`, A/B test and rollback targets) can never lose an instance, so every
    ///   owner of it is refused and the surplus is deferred until #1497 changes those writers.
    static BiFunction<NodeId, Set<NodeId>, Option<DrainRefusal>> minAvailableDrainGuard(KVStore<AetherKey, AetherValue> kvStore) {
        return (candidate, remainingNodes) -> firstRefusal(kvStore, candidate, remainingNodes);
    }

    /// Why the guard refused to drain `owner`: `artifact` would be left with `remainingActive` ACTIVE
    /// instances on the remaining nodes, below its `minAvailable`. Carried into the reconciler's
    /// deferral WARN so an operator can see which owner and slice held the surplus back.
    record DrainRefusal(NodeId owner, Artifact artifact, long remainingActive, int minAvailable) {
        static DrainRefusal drainRefusal(NodeId owner, Artifact artifact, long remainingActive, int minAvailable) {
            return new DrainRefusal(owner, artifact, remainingActive, minAvailable);
        }
    }

    private static Option<DrainRefusal> firstRefusal(KVStore<AetherKey, AetherValue> kvStore,
                                                     NodeId candidate,
                                                     Set<NodeId> remainingNodes) {
        var placements = livePlacements(kvStore);
        var refusals = hostedArtifacts(placements, candidate).stream()
                                      .sorted(Comparator.comparing(Artifact::asString))
                                      .flatMap(artifact -> refusalFor(kvStore,
                                                                      placements,
                                                                      candidate,
                                                                      artifact,
                                                                      remainingNodes).stream());

        return Option.from(refusals.findFirst());
    }

    /// The distinct artifacts `node` holds a LIVE placement of.
    private static Set<Artifact> hostedArtifacts(Map<NodeArtifactKey, SliceState> placements, NodeId node) {
        return placements.keySet()
                         .stream()
                         .filter(key -> key.isForNode(node))
                         .map(NodeArtifactKey::artifact)
                         .collect(Collectors.toSet());
    }

    private static Option<DrainRefusal> refusalFor(KVStore<AetherKey, AetherValue> kvStore,
                                                   Map<NodeArtifactKey, SliceState> placements,
                                                   NodeId candidate,
                                                   Artifact artifact,
                                                   Set<NodeId> remainingNodes) {
        var remaining = remainingActive(placements, artifact, remainingNodes);
        var required = minAvailable(kvStore, artifact);

        return remaining >= required
               ? none()
               : some(DrainRefusal.drainRefusal(candidate, artifact, remaining, required));
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
                                        Set<NodeId> remainingNodes) {
        return placements.entrySet()
                         .stream()
                         .filter(placement -> isRemainingActiveInstance(placement, artifact, remainingNodes))
                         .count();
    }

    /// An ACTIVE instance of `artifact` on one of `remainingNodes` — a placement on a non-member, on a
    /// node already departing, or on a victim of this pass is not availability.
    private static boolean isRemainingActiveInstance(Map.Entry<NodeArtifactKey, SliceState> placement,
                                                     Artifact artifact,
                                                     Set<NodeId> remainingNodes) {
        var key = placement.getKey();

        return key.artifact()
                  .equals(artifact)
               && remainingNodes.contains(key.nodeId())
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
