// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
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
/// Ownership is read from the per-node slice rows of the KV-Store, through the engine's [SliceRowSource]:
/// `NodeArtifactKey(nodeId, artifact) → NodeArtifactValue(state, ...)` under LEGACY, and under FSM2 the
/// CURRENT `SliceObservedKey` rows (deployment state machine spec 1.6.1, NT-110): a row stale on positive
/// evidence and the UNLOADED tombstone read as absent, so a node holding only those owns nothing and
/// counts toward no `remainingActive`. A node OWNS an
/// active slice when it has at least one entry whose [`SliceState`] is LIVE — anything except the
/// terminal/teardown states FAILED / UNLOAD / UNLOADING (mirrors the FSM's `isLiveState`; on FSM2 rows
/// this is exactly spec R-1.6.2's positive `isLive()` list). A LIVE
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
    static Predicate<NodeId> ownsActiveSlices(KVStore<AetherKey, AetherValue> kvStore, SliceRowSource rows) {
        return nodeId -> nodeHasLiveSlice(kvStore, rows, nodeId);
    }

    private static boolean nodeHasLiveSlice(KVStore<AetherKey, AetherValue> kvStore,
                                            SliceRowSource rows,
                                            NodeId nodeId) {
        var found = new AtomicBoolean(false);

        rows.forEachRow(kvStore, (node, _, state) -> recordIfLiveOwnership(found, nodeId, node, state));

        return found.get();
    }

    private static void recordIfLiveOwnership(AtomicBoolean found, NodeId nodeId, NodeId node, SliceState state) {
        if (node.equals(nodeId) && isLiveState(state)) {
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
    /// - A slice whose `minAvailable` equals its instance count can never lose an instance, so every
    ///   owner of it is refused and the surplus is deferred. Since #1497 every writer without an explicit
    ///   value uses `ceil(instances/2)`, so this holds only for a single-instance slice (floor 1) or an
    ///   operator who set the floor to the count.
    static BiFunction<NodeId, Set<NodeId>, Option<DrainRefusal>> minAvailableDrainGuard(KVStore<AetherKey, AetherValue> kvStore,
                                                                                        SliceRowSource rows) {
        return (candidate, remainingNodes) -> firstRefusal(kvStore, rows, candidate, remainingNodes);
    }

    /// The same availability test as [#minAvailableDrainGuard], answering EVERY hosted slice that draining
    /// `candidate` would drop below its `minAvailable`, sorted by artifact, not just the first (#1720). The operator
    /// drain and shutdown routes need all of them: the refusal names each slice, and a forced breach warns about
    /// each. Empty when every hosted slice keeps its floor. Same inputs, same counting rules.
    static BiFunction<NodeId, Set<NodeId>, List<DrainRefusal>> minAvailableDrainViolations(KVStore<AetherKey, AetherValue> kvStore,
                                                                                           SliceRowSource rows) {
        return (candidate, remainingNodes) -> refusalStream(kvStore, rows, candidate, remainingNodes).toList();
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
                                                     SliceRowSource rows,
                                                     NodeId candidate,
                                                     Set<NodeId> remainingNodes) {
        return Option.from(refusalStream(kvStore, rows, candidate, remainingNodes).findFirst());
    }

    private static java.util.stream.Stream<DrainRefusal> refusalStream(KVStore<AetherKey, AetherValue> kvStore,
                                                                       SliceRowSource rows,
                                                                       NodeId candidate,
                                                                       Set<NodeId> remainingNodes) {
        var placements = livePlacements(kvStore, rows);

        return hostedArtifacts(placements, candidate).stream()
                              .sorted(Comparator.comparing(Artifact::asString))
                              .flatMap(artifact -> refusalFor(kvStore, placements, candidate, artifact, remainingNodes).stream());
    }

    /// The distinct artifacts `node` holds a LIVE placement of.
    private static Set<Artifact> hostedArtifacts(Map<Placement, SliceState> placements, NodeId node) {
        return placements.keySet()
                         .stream()
                         .filter(key -> key.node()
                                           .equals(node))
                         .map(Placement::artifact)
                         .collect(Collectors.toSet());
    }

    private static Option<DrainRefusal> refusalFor(KVStore<AetherKey, AetherValue> kvStore,
                                                   Map<Placement, SliceState> placements,
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
    private static Map<Placement, SliceState> livePlacements(KVStore<AetherKey, AetherValue> kvStore,
                                                             SliceRowSource rows) {
        var placements = new HashMap<Placement, SliceState>();

        rows.forEachRow(kvStore, (node, artifact, state) -> recordIfLive(placements, node, artifact, state));

        return placements;
    }

    @Contract
    private static void recordIfLive(Map<Placement, SliceState> placements,
                                     NodeId node,
                                     Artifact artifact,
                                     SliceState state) {
        if (isLiveState(state)) {
            placements.put(new Placement(node, artifact), state);
        }
    }

    private static long remainingActive(Map<Placement, SliceState> placements,
                                        Artifact artifact,
                                        Set<NodeId> remainingNodes) {
        return placements.entrySet()
                         .stream()
                         .filter(placement -> isRemainingActiveInstance(placement, artifact, remainingNodes))
                         .count();
    }

    /// An ACTIVE instance of `artifact` on one of `remainingNodes` — a placement on a non-member, on a
    /// node already departing, or on a victim of this pass is not availability.
    private static boolean isRemainingActiveInstance(Map.Entry<Placement, SliceState> placement,
                                                     Artifact artifact,
                                                     Set<NodeId> remainingNodes) {
        var key = placement.getKey();

        return key.artifact()
                  .equals(artifact)
               && remainingNodes.contains(key.node())
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

    /// One `(node, artifact)` placement, whichever engine's row it was read from.
    record Placement(NodeId node, Artifact artifact) {}

    record unused() implements SliceOwnershipQuery {}
}
