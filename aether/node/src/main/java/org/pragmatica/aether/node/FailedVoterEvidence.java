// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #2098: the positive evidence of failure the core voter reconciler needs before it may remove a voter. A node is failed when the leader
/// OBSERVED it reporting a state other than READY, or membership holds it Dead or Departing. A node absent from both is merely unobserved and
/// is never failed on that account, so a fresh leader cannot drop a healthy voter it has not heard from yet.
///
/// The two sources are the leader's reported-state view (the metrics collector) and the membership verdicts; both are read afresh on
/// every call, so the set follows the cluster rather than a snapshot taken at wiring time.
final class FailedVoterEvidence {
    private FailedVoterEvidence() {}

    /// The node's own wiring: the collector's reported-state view and the live membership FSM (not yet built when the wiring is made,
    /// hence a supplier; none yet means no verdicts).
    static Supplier<Set<NodeId>> forNode(ClusterSyncCollector collector, Supplier<MembershipFsm> membership) {
        return failedVoterEvidence(collector::reportedStates,
                                   () -> Option.option(membership.get())
                                               .map(MembershipFsm::memberStates)
                                               .or(Map.of()));
    }

    static Supplier<Set<NodeId>> failedVoterEvidence(Supplier<Map<NodeId, NodeReportedState>> reportedStates,
                                                     Supplier<Map<NodeId, String>> memberStates) {
        return () -> failedVoters(observedNotReady(reportedStates.get()), memberStates.get());
    }

    /// The nodes a report was OBSERVED for whose state is not READY.
    static Set<NodeId> observedNotReady(Map<NodeId, NodeReportedState> states) {
        return states.entrySet()
                     .stream()
                     .filter(entry -> entry.getValue() != NodeReportedState.READY)
                     .map(Map.Entry::getKey)
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// The nodes with positive evidence of failure: observed not READY, or a membership verdict of Dead/Departing.
    static Set<NodeId> failedVoters(Set<NodeId> observedNotReady, Map<NodeId, String> memberStates) {
        var gone = memberStates.entrySet()
                               .stream()
                               .filter(entry -> !NodeReplacementWiring.alive(Option.some(entry.getValue())))
                               .map(Map.Entry::getKey);

        return Stream.concat(observedNotReady.stream(), gone)
                     .collect(Collectors.toUnmodifiableSet());
    }
}
