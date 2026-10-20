// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Turns admitted, synchronized CORE candidates into a concrete Rabia §4 reconfiguration command.
/// Capacity intent cannot change the electorate until enough actual candidates exist.
///
/// Leader-driven and serial: a new target is requested only while the installed roster is settled —
/// `settled` (the engine's retirement-safe roster) reports it only when no reconfiguration is pending
/// and every member added by the last applied change has caught up past that change's slot R.
///
/// #1543: a committed replacement pairing (original → replacement) whose phase authorizes the swap lets a ready
/// replacement displace its original — one member out, one in, the voter count unchanged — once the roster is
/// otherwise healthy. Without a pairing the selection is exactly the unpaired one.
///
/// #2098: unknown is not unhealthy. A leader that has just taken over has observed readiness for few voters, and a voter it has
/// not observed ranks as unready. A current voter is therefore removable only on positive evidence of failure (`failed`: a
/// reported non-READY state, or a membership verdict of Dead/Departing), never because no readiness was seen, however long
/// ago the leader took over; a voter without such evidence is treated as healthy, so a heal drops no healthy voter and a
/// paired swap's leaver stays the original.
public interface CoreVoterReconciler {
    Promise<Unit> reconcile();

    static CoreVoterReconciler coreVoterReconciler(NodeId self,
                                                   BooleanSupplier leader,
                                                   Supplier<Option<VoterConfiguration>> installed,
                                                   Supplier<Option<VoterConfiguration>> settled,
                                                   IntSupplier desired,
                                                   Supplier<Set<NodeId>> readyCoreCandidates,
                                                   Supplier<Map<NodeId, NodeId>> voterSwaps,
                                                   Supplier<Set<NodeId>> failed,
                                                   Function<ClusterConfig, Promise<Unit>> reconfigure) {
        record reconciler(NodeId self,
                          BooleanSupplier leader,
                          Supplier<Option<VoterConfiguration>> installed,
                          Supplier<Option<VoterConfiguration>> settled,
                          IntSupplier desired,
                          Supplier<Set<NodeId>> readyCoreCandidates,
                          Supplier<Map<NodeId, NodeId>> voterSwaps,
                          Supplier<Set<NodeId>> failed,
                          Function<ClusterConfig, Promise<Unit>> reconfigure,
                          AtomicBoolean running) implements CoreVoterReconciler {
            @Override
            public Promise<Unit> reconcile() {
                if (!leader.getAsBoolean() || !running.compareAndSet(false, true)) {
                    return Promise.unitPromise();
                }

                return installed.get()
                                .fold(Promise::unitPromise, this::reconcileInstalled)
                                .timeout(TimeSpan.timeSpan(30).seconds())
                                .withResult(_ -> running.set(false));
            }

            private Promise<Unit> reconcileInstalled(VoterConfiguration current) {
                var down = failed.get();
                var ready = readyCoreCandidates.get()
                                               .stream()
                                               .filter(node -> !down.contains(node))
                                               .collect(java.util.stream.Collectors.toUnmodifiableSet());
                var assumed = current.members()
                                     .stream()
                                     .filter(node -> !down.contains(node) && !ready.contains(node))
                                     .collect(java.util.stream.Collectors.toUnmodifiableSet());
                var swaps = voterSwaps.get();
                var target = selectVoters(self, current, ready, assumed, desired.getAsInt(), swaps);

                if (target.isEmpty() || Set.copyOf(target).equals(Set.copyOf(current.members())) || shrinksDuringASwap(target,
                                                                                                                       current,
                                                                                                                       swaps) || settled.get()
                                                                                                                                        .filter(current::equals)
                                                                                                                                        .isEmpty()) {
                    return Promise.unitPromise();
                }

                return ClusterConfig.clusterConfig(target)
                                    .async()
                                    .flatMap(reconfigure::apply);
            }

            /// A surplus trim is a size reduction, not a heal; it never runs while a swap is in progress (#2098 Mode 1's trigger).
            private boolean shrinksDuringASwap(List<NodeId> target,
                                               VoterConfiguration current,
                                               Map<NodeId, NodeId> swaps) {
                return target.size() < current.members()
                                              .size() && !swaps.isEmpty();
            }
        }

        return new reconciler(self,
                              leader,
                              installed,
                              settled,
                              desired,
                              readyCoreCandidates,
                              voterSwaps,
                              failed,
                              reconfigure,
                              new AtomicBoolean());
    }

    static List<NodeId> selectVoters(NodeId self, VoterConfiguration current, Set<NodeId> ready, int desired) {
        return selectVoters(self, current, ready, Set.of(), desired);
    }

    /// `assumed` are current voters the leader has not observed and holds no evidence against (#2098): they rank below an observed-ready voter
    /// and above any newcomer and any voter with evidence of failure, so a surplus trim removes failed voters first, then unobserved ones,
    /// then healthy ones (id order only breaks ties), and a newcomer never displaces an unobserved voter.
    static List<NodeId> selectVoters(NodeId self,
                                     VoterConfiguration current,
                                     Set<NodeId> ready,
                                     Set<NodeId> assumed,
                                     int desired) {
        if (desired < 1 || desired % 2 == 0) {
            return List.of();
        }

        var existing = Set.copyOf(current.members());
        var candidates = Stream.concat(current.members().stream(),
                                       ready.stream())
                               .distinct()
                               .sorted(Comparator.<NodeId> comparingInt(node -> rank(node,
                                                                                     self,
                                                                                     existing,
                                                                                     ready,
                                                                                     assumed)).thenComparing(NodeId::id))
                               .limit(desired)
                               .toList();

        return candidates.size() == desired
               ? candidates
               : List.of();
    }

    /// The unpaired selection, then at most ONE authorized swap applied to it — and only when that selection leaves
    /// the roster unchanged, so a swap never rides along with a heal and every reconfiguration changes one seat.
    /// The leader's own seat MAY be swapped (#1543 E): at minimum topology draining a voter is refused by the disruption
    /// budget, so the old leader leaves the electorate by this one-out-one-in reconfiguration (the size stays N), a
    /// successor is elected by the ordinary Rabia election, and the drain that follows passes because the old node no longer
    /// votes. The unpaired selection still ranks the leader first, so a heal never displaces it.
    static List<NodeId> selectVoters(NodeId self,
                                     VoterConfiguration current,
                                     Set<NodeId> ready,
                                     int desired,
                                     Map<NodeId, NodeId> swaps) {
        return selectVoters(self, current, ready, Set.of(), desired, swaps);
    }

    static List<NodeId> selectVoters(NodeId self,
                                     VoterConfiguration current,
                                     Set<NodeId> ready,
                                     Set<NodeId> assumed,
                                     int desired,
                                     Map<NodeId, NodeId> swaps) {
        var unpaired = selectVoters(self, current, ready, assumed, desired);

        if (unpaired.isEmpty() || !Set.copyOf(unpaired).equals(Set.copyOf(current.members()))) {
            return unpaired;
        }

        return Option.from(swaps.entrySet()
                                .stream()
                                .filter(swap -> eligibleSwap(self,
                                                             current,
                                                             ready,
                                                             swap.getKey(),
                                                             swap.getValue()))
                                .min(Comparator.comparing(swap -> swap.getKey()
                                                                      .id())))
                     .map(swap -> swapped(unpaired,
                                          swap.getKey(),
                                          swap.getValue()))
                     .or(unpaired);
    }

    private static boolean eligibleSwap(NodeId self,
                                        VoterConfiguration current,
                                        Set<NodeId> ready,
                                        NodeId original,
                                        NodeId replacement) {
        return current.members()
                      .contains(original)
               && !current.members()
                          .contains(replacement)
               && ready.contains(replacement);
    }

    private static List<NodeId> swapped(List<NodeId> voters, NodeId original, NodeId replacement) {
        return voters.stream()
                     .map(node -> node.equals(original)
                                  ? replacement
                                  : node)
                     .toList();
    }

    private static int rank(NodeId node, NodeId self, Set<NodeId> existing, Set<NodeId> ready, Set<NodeId> assumed) {
        if (node.equals(self)) {
            return 0;
        }

        if (ready.contains(node)) {
            return existing.contains(node)
                   ? 1
                   : 3;
        }

        return assumed.contains(node)
               ? 2
               : 4;
    }
}
