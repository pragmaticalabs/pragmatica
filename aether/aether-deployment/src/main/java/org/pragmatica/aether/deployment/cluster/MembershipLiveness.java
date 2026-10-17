// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #1050 / #1062 — the membership and liveness evidence the CTM consults before any IRREVERSIBLE reap: the
/// drain-grace backstop, the departed-node reap, and the activation replay. Every read is a live supplier,
/// evaluated at the moment of the decision; nothing is cached.
///
/// - `coreCountedMembers` — `MembershipFsm.coreCountedMembers()`: role-scoped MEMBER + SUSPECT. The same
///   denominator the `LeaderReconciler` decides drains with, so quorum safety here agrees with its pass log.
/// - `trackedMembers` — `MembershipFsm.broadcastEligibleMembers()`: every member NOT terminally DEAD
///   (OBSERVED + MEMBER + SUSPECT + DEPARTING), role-blind. A booting or draining node is tracked.
/// - `swimAlive` — raw SWIM health HEALTHY or SUSPECTED: a signal independent of the FSM verdict.
/// - `transportConnected` — the leader's own cluster transport link to the node is up.
/// - `inFlightProvisioning` — replacements dispatched but not yet joined: this leader's reconciler
///   in-flight set plus the set retained from the previous leader's pings.
/// - `configuredCoreCount` — the configured core size; below 1 means unknown, and every consumer treats an
///   unknown size as NOT quorum-safe (fail-closed).
/// - `advertisedRole` (#689) — `MembershipFsm.memberDescriptor(id).role()`: the role the node SELF-ASSERTS,
///   as the FSM holds it after its blank-downgrade merge — the same value the projector classified the
///   node's join by. `none()` when the FSM does not track the id at all. Never the `TopologyObserver`'s
///   first sighting, which is frozen (`putIfAbsent`) and label-less when the node was learned by gossip.
public record MembershipLiveness(Supplier<Set<NodeId>> coreCountedMembers,
                                 Supplier<Set<NodeId>> trackedMembers,
                                 Predicate<NodeId> swimAlive,
                                 Predicate<NodeId> transportConnected,
                                 Supplier<Set<NodeId>> inFlightProvisioning,
                                 IntSupplier configuredCoreCount,
                                 Function<NodeId, Option<String>> advertisedRole) {
    /// No evidence at all — for hosts and tests without membership wiring. Every reap gate that needs quorum
    /// safety refuses (configured size unknown), nothing is reported live, and no node has an advertised role
    /// to compare (#689: no comparison, never a fabricated blank).
    public static final MembershipLiveness UNWIRED = new MembershipLiveness(Set::of,
                                                                            Set::of,
                                                                            _ -> false,
                                                                            _ -> false,
                                                                            Set::of,
                                                                            () -> 0,
                                                                            _ -> Option.none());

    public static MembershipLiveness membershipLiveness(Supplier<Set<NodeId>> coreCountedMembers,
                                                        Supplier<Set<NodeId>> trackedMembers,
                                                        Predicate<NodeId> swimAlive,
                                                        Predicate<NodeId> transportConnected,
                                                        Supplier<Set<NodeId>> inFlightProvisioning,
                                                        IntSupplier configuredCoreCount,
                                                        Function<NodeId, Option<String>> advertisedRole) {
        return new MembershipLiveness(coreCountedMembers,
                                      trackedMembers,
                                      swimAlive,
                                      transportConnected,
                                      inFlightProvisioning,
                                      configuredCoreCount,
                                      advertisedRole);
    }

    /// R1′(a), refined after verify-1058: LIVE by liveness EVIDENCE, never by membership projection. A node is live
    /// when raw SWIM reports it HEALTHY or SUSPECTED, OR the active leader's own transport link to it is connected.
    /// A DEPARTING target whose DRAIN was never delivered (for example, withdrawn to MEMBER and re-drained) is
    /// therefore still live. NOT live requires positive evidence of death or exit: raw SWIM FAULTY or UNKNOWN (a
    /// departed or forgotten member) AND the leader's transport link down. A surplus trim never reaps a live node.
    public boolean live(NodeId nodeId) {
        return swimAlive.test(nodeId) || transportConnected.test(nodeId);
    }

    /// #1062: ANY evidence of life — [#live] (raw SWIM or the leader's transport link), or a counted membership. A
    /// reap facing such evidence is deferred, never executed.
    public boolean demonstrablyLive(NodeId nodeId) {
        return live(nodeId) || coreCountedMembers.get()
                                                 .contains(nodeId);
    }

    /// R4: an instance of such a node is never touched by the activation replay — it is tracked by the FSM,
    /// shows independent evidence of life, or is a replacement still booting.
    public boolean replayProtected(NodeId nodeId) {
        return trackedMembers.get()
                             .contains(nodeId) || demonstrablyLive(nodeId) || inFlightProvisioning.get()
                                                                                                  .contains(nodeId);
    }

    /// SF-1 (verify-1057-r3): [#replayProtected] holds for `nodeId` ONLY because raw SWIM still reports it alive —
    /// untracked, uncounted, not in flight, transport down. That is the shape of a dead node inside SWIM's suspicion
    /// window seen by a leader that has no parked reap for it; the replay parks such a node so the FAULTY edge that
    /// ends the window re-arms the reap. Any other protection (tracked, counted, in flight, link up) is not parked:
    /// the FSM's own departure path, or a later replay, owns it.
    public boolean swimOnlyProtected(NodeId nodeId) {
        return swimAlive.test(nodeId)
               && !transportConnected.test(nodeId)
               && !knownToTheCluster(nodeId);
    }

    /// Tracked or counted by the FSM, or a replacement still in flight — every protection but raw SWIM life.
    private boolean knownToTheCluster(NodeId nodeId) {
        var tracked = trackedMembers.get();
        var counted = coreCountedMembers.get();
        var inFlight = inFlightProvisioning.get();

        return tracked.contains(nodeId) || counted.contains(nodeId) || inFlight.contains(nodeId);
    }

    /// The evidence behind a liveness decision, for the log line that records it.
    public String evidence(NodeId nodeId) {
        return "transportConnected=" + transportConnected.test(nodeId)
             + ", swimAlive=" + swimAlive.test(nodeId)
             + ", counted=" + coreCountedMembers.get()
                                                .contains(nodeId);
    }
}
