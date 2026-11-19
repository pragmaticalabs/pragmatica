// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.function.Function;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #1880 — the drain-safety grace, the one rule every drain passes: no drain for a reason
/// [`DrainReason#subjectToDrainSafetyGrace`] holds targets a node younger than [`#window`], with age counted from first
/// reachability evidence (`MembershipFsm.memberAgeMs`: 0 before it, so a seed promoted at wiring that joins late is young
/// at its join). [`ClusterTopologyManager#drainNode`] enforces it; the `LeaderReconciler` builds this instance and selects
/// surplus victims through it, so selection never picks a victim the choke point refuses and defers the rest.
///
/// Why a young node is held: it is a member whose identity is not yet established — its self-asserted role is still
/// propagating (join ANNOUNCE, QUIC Hello, gossiped labels), and a just-joined core sits outside the voter roster only
/// because admission has not finished. Either reads as a surplus that is not one.
///
/// An untracked node (no age) reads mature: the grace guards a member's first seconds, and a node the membership has
/// never tracked is no such member. The unwired instance ([`#unwired`]) has no age source and therefore holds nothing.
///
/// @param window      the grace, `2 × splitTimeout`: at least the ×1 deficit debounce and above the worst-case role
///                    propagation (seconds), tied to the single membership timing constant rather than a new literal
/// @param memberAgeMs the age source, `MembershipFsm.memberAgeMs`
public record DrainSafetyGrace(TimeSpan window, Function<NodeId, Option<Long>> memberAgeMs) {
    /// The production grace for a membership `splitTimeout` (`nttDepartureTimeout`), read from `memberAgeMs`.
    public static DrainSafetyGrace drainSafetyGrace(TimeSpan splitTimeout, Function<NodeId, Option<Long>> memberAgeMs) {
        return new DrainSafetyGrace(timeSpan(splitTimeout.nanos() * 2).nanos(),
                                    memberAgeMs);
    }

    /// The grace of a manager no reconciler has wired: no age source, so every node reads mature and nothing is held.
    public static DrainSafetyGrace unwired() {
        return new DrainSafetyGrace(timeSpan(0).nanos(), _ -> none());
    }

    /// Why a drain of `target` for `reason` is refused now, or `none()` when the grace allows it.
    public Option<String> refusal(NodeId target, DrainReason reason) {
        return reason.subjectToDrainSafetyGrace()
               ? youngAgeMs(target).map(ageMs -> "Node " + target.id()
                                                + " is " + ageMs
                                                + " ms old, inside the " + window.millis()
                                                + " ms drain-safety grace (" + reason
                                                + ")")
               : none();
    }

    /// Whether a drain of `target` for `reason` is allowed now ([`#refusal`] is empty).
    public boolean permits(NodeId target, DrainReason reason) {
        return refusal(target, reason).isEmpty();
    }

    /// `target`'s age (ms) when it is readable and below [`#window`] — the young members the grace holds.
    public Option<Long> youngAgeMs(NodeId target) {
        return memberAgeMs.apply(target)
                          .filter(ageMs -> ageMs < window.millis());
    }
}
