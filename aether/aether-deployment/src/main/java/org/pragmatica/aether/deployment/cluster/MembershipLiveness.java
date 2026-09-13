// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;


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
public record MembershipLiveness(Supplier<Set<NodeId>> coreCountedMembers,
                                 Supplier<Set<NodeId>> trackedMembers,
                                 Predicate<NodeId> swimAlive,
                                 Predicate<NodeId> transportConnected,
                                 Supplier<Set<NodeId>> inFlightProvisioning,
                                 IntSupplier configuredCoreCount) {
    /// No evidence at all — for hosts and tests without membership wiring. Every reap gate that needs quorum
    /// safety refuses (configured size unknown), and nothing is reported live.
    public static final MembershipLiveness UNWIRED = new MembershipLiveness(Set::of,
                                                                            Set::of,
                                                                            _ -> false,
                                                                            _ -> false,
                                                                            Set::of,
                                                                            () -> 0);

    public static MembershipLiveness membershipLiveness(Supplier<Set<NodeId>> coreCountedMembers,
                                                        Supplier<Set<NodeId>> trackedMembers,
                                                        Predicate<NodeId> swimAlive,
                                                        Predicate<NodeId> transportConnected,
                                                        Supplier<Set<NodeId>> inFlightProvisioning,
                                                        IntSupplier configuredCoreCount) {
        return new MembershipLiveness(coreCountedMembers,
                                      trackedMembers,
                                      swimAlive,
                                      transportConnected,
                                      inFlightProvisioning,
                                      configuredCoreCount);
    }

    /// R1′(a): a live, counted member — counted (so neither DEPARTING nor DEAD) AND alive by raw SWIM. A
    /// surplus trim never reaps such a node, whoever is leader.
    public boolean liveCountedMember(NodeId nodeId) {
        return coreCountedMembers.get()
                                 .contains(nodeId) && swimAlive.test(nodeId);
    }

    /// #1062: ANY independent evidence of life — the leader's transport link, raw SWIM, or a counted
    /// membership. A reap facing such evidence is deferred, never executed.
    public boolean demonstrablyLive(NodeId nodeId) {
        return transportConnected.test(nodeId) || swimAlive.test(nodeId) || coreCountedMembers.get()
                                                                                              .contains(nodeId);
    }

    /// R4: an instance of such a node is never touched by the activation replay — it is tracked by the FSM,
    /// shows independent evidence of life, or is a replacement still booting.
    public boolean replayProtected(NodeId nodeId) {
        return trackedMembers.get()
                             .contains(nodeId) || demonstrablyLive(nodeId) || inFlightProvisioning.get()
                                                                                                  .contains(nodeId);
    }

    /// The evidence behind a liveness decision, for the log line that records it.
    public String evidence(NodeId nodeId) {
        return "transportConnected=" + transportConnected.test(nodeId)
             + ", swimAlive=" + swimAlive.test(nodeId)
             + ", counted=" + coreCountedMembers.get()
                                                .contains(nodeId);
    }
}
