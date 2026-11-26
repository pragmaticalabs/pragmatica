// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Comparator;
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
/// OBSERVED it reporting a state other than READY and SYNCING (SYNCING is what a healthy follower reports across a leader change), or membership holds it Dead or Departing. A node absent from both is merely unobserved and
/// is never failed on that account, so a fresh leader cannot drop a healthy voter it has not heard from yet.
///
/// #2136: a voter stuck in SYNCING ([StuckSyncingVoters]: one sustained episode of [StuckSyncingVoters#STUCK_BOUND]) is evidence too,
/// but for AT MOST ONE voter at a time, and only while NO voter of the installed roster has other evidence (Dead, Departing, observed
/// neither READY nor SYNCING); among several stuck voters the longest episode is chosen, ties by id. So a heal resting on SYNCING changes
/// one seat: the reconciler brings in only admitted READY candidates (none = nothing removed) and requests a new roster only once the
/// installed one has settled, so the next stuck voter is counted only after that change applied and settled. The heal on other evidence
/// is unchanged: it may replace several failed voters in one change, bounded by the engine's retained-majority rule. A surplus trim keeps
/// its own ranking, in which a stuck voter counted here ranks as failed (removed first).
///
/// A healed voter that is still alive is no longer a voter but stays a core: it leaves through the same path as a healthy surplus core,
/// the leader's voter-excluded core retirement (`TopologyAssembly.retireExcludedCores`): only once it hosts nothing and is past the
/// drain-safety grace, and then as an `OVERPROVISION_SCALE_DOWN` drain, never by a kill.
///
/// The sources are the leader's reported-state view (the metrics collector), the membership verdicts and the installed roster; all are
/// read afresh on every call, so the set follows the cluster rather than a snapshot taken at wiring time. Every call is also an
/// evaluation of the stuck-voter episodes.
public final class FailedVoterEvidence {
    private static final Comparator<Map.Entry<NodeId, Long>> LONGEST_EPISODE_FIRST = Comparator.<Map.Entry<NodeId, Long>> comparingLong(Map.Entry::getValue)
                                                                                               .reversed()
                                                                                               .thenComparing(entry -> entry.getKey()
                                                                                                                            .id());

    private FailedVoterEvidence() {}

    /// The node's own wiring: the collector's reported-state view, the live membership FSM (not yet built when the wiring is made,
    /// hence a supplier; none yet means no verdicts), the installed voters and the node's stuck-voter episodes.
    public static Supplier<Set<NodeId>> forNode(ClusterSyncCollector collector,
                                                Supplier<MembershipFsm> membership,
                                                Supplier<Set<NodeId>> voters,
                                                StuckSyncingVoters stuck) {
        return failedVoterEvidence(collector::reportedStates,
                                   () -> Option.option(membership.get())
                                               .map(MembershipFsm::memberStates)
                                               .or(Map.of()),
                                   voters,
                                   stuck);
    }

    static Supplier<Set<NodeId>> failedVoterEvidence(Supplier<Map<NodeId, NodeReportedState>> reportedStates,
                                                     Supplier<Map<NodeId, String>> memberStates,
                                                     Supplier<Set<NodeId>> voters,
                                                     StuckSyncingVoters stuck) {
        return () -> evidence(reportedStates.get(), memberStates.get(), voters.get(), stuck);
    }

    static Set<NodeId> evidence(Map<NodeId, NodeReportedState> reported,
                                Map<NodeId, String> memberStates,
                                Set<NodeId> voters,
                                StuckSyncingVoters stuck) {
        return withAtMostOneStuck(failedVoters(observedNotReady(reported), memberStates),
                                  voters,
                                  stuck.stuck(voters, reported));
    }

    /// `failed`, plus the longest-stuck voter (ties by id) only when no voter of `voters` is in `failed`.
    static Set<NodeId> withAtMostOneStuck(Set<NodeId> failed, Set<NodeId> voters, Map<NodeId, Long> stuck) {
        if (voters.stream().anyMatch(failed::contains)) {
            return failed;
        }

        return longestStuck(stuck).map(voter -> Stream.concat(failed.stream(),
                                                              Stream.of(voter))
                                                      .collect(Collectors.toUnmodifiableSet()))
                           .or(failed);
    }

    private static Option<NodeId> longestStuck(Map<NodeId, Long> stuck) {
        return Option.from(stuck.entrySet().stream().min(LONGEST_EPISODE_FIRST).map(Map.Entry::getKey));
    }

    /// The nodes a report was OBSERVED for whose state is neither READY nor SYNCING. A SYNCING report alone is not evidence: the state holder
    /// reports it whenever consensus is inactive, which a healthy follower is across a leader change (only a sustained episode is, #2136).
    static Set<NodeId> observedNotReady(Map<NodeId, NodeReportedState> states) {
        return states.entrySet()
                     .stream()
                     .filter(entry -> entry.getValue() != NodeReportedState.READY && entry.getValue() != NodeReportedState.SYNCING)
                     .map(Map.Entry::getKey)
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// The nodes with positive evidence of failure: observed not READY, or a membership verdict of Dead/Departing.
    static Set<NodeId> failedVoters(Set<NodeId> observedNotReady, Map<NodeId, String> memberStates) {
        var gone = memberStates.entrySet()
                               .stream()
                               .filter(entry -> !NodeReplacementWiring.alive(Option.some(entry.getValue())))
                               .map(Map.Entry::getKey);

        return Stream.concat(observedNotReady.stream(),
                             gone)
                     .collect(Collectors.toUnmodifiableSet());
    }
}
