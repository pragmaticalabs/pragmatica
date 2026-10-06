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
public interface CoreVoterReconciler {
    Promise<Unit> reconcile();

    static CoreVoterReconciler coreVoterReconciler(NodeId self,
                                                   BooleanSupplier leader,
                                                   Supplier<Option<VoterConfiguration>> installed,
                                                   Supplier<Option<VoterConfiguration>> settled,
                                                   IntSupplier desired,
                                                   Supplier<Set<NodeId>> readyCoreCandidates,
                                                   Supplier<Map<NodeId, NodeId>> voterSwaps,
                                                   Function<ClusterConfig, Promise<Unit>> reconfigure) {
        record reconciler(NodeId self,
                          BooleanSupplier leader,
                          Supplier<Option<VoterConfiguration>> installed,
                          Supplier<Option<VoterConfiguration>> settled,
                          IntSupplier desired,
                          Supplier<Set<NodeId>> readyCoreCandidates,
                          Supplier<Map<NodeId, NodeId>> voterSwaps,
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
                                .onResultRun(() -> running.set(false));
            }

            private Promise<Unit> reconcileInstalled(VoterConfiguration current) {
                var target = selectVoters(self, current, readyCoreCandidates.get(), desired.getAsInt(), voterSwaps.get());

                if (target.isEmpty() || Set.copyOf(target).equals(Set.copyOf(current.members())) || settled.get()
                                                                                                           .filter(current::equals)
                                                                                                           .isEmpty()) {
                    return Promise.unitPromise();
                }

                return ClusterConfig.clusterConfig(target)
                                    .async()
                                    .flatMap(reconfigure::apply);
            }
        }

        return new reconciler(self,
                              leader,
                              installed,
                              settled,
                              desired,
                              readyCoreCandidates,
                              voterSwaps,
                              reconfigure,
                              new AtomicBoolean());
    }

    static List<NodeId> selectVoters(NodeId self, VoterConfiguration current, Set<NodeId> ready, int desired) {
        if (desired < 1 || desired % 2 == 0) {
            return List.of();
        }

        var existing = Set.copyOf(current.members());
        var candidates = Stream.concat(current.members().stream(),
                                       ready.stream())
                               .distinct()
                               .sorted(Comparator.<NodeId> comparingInt(node -> rank(node, self, existing, ready)).thenComparing(NodeId::id))
                               .limit(desired)
                               .toList();

        return candidates.size() == desired
               ? candidates
               : List.of();
    }

    /// The unpaired selection, then at most ONE authorized swap applied to it — and only when that selection leaves
    /// the roster unchanged, so a swap never rides along with a heal and every reconfiguration changes one seat.
    /// The leader's own seat is never swapped (`rank(self)=0`): the leader is replaced last, by drain.
    static List<NodeId> selectVoters(NodeId self,
                                     VoterConfiguration current,
                                     Set<NodeId> ready,
                                     int desired,
                                     Map<NodeId, NodeId> swaps) {
        var unpaired = selectVoters(self, current, ready, desired);

        if (unpaired.isEmpty() || !Set.copyOf(unpaired).equals(Set.copyOf(current.members()))) {
            return unpaired;
        }

        return Option.from(swaps.entrySet()
                                .stream()
                                .filter(swap -> eligibleSwap(self, current, ready, swap.getKey(), swap.getValue()))
                                .min(Comparator.comparing(swap -> swap.getKey().id())))
                     .map(swap -> swapped(unpaired, swap.getKey(), swap.getValue()))
                     .or(unpaired);
    }

    private static boolean eligibleSwap(NodeId self,
                                        VoterConfiguration current,
                                        Set<NodeId> ready,
                                        NodeId original,
                                        NodeId replacement) {
        return !original.equals(self) && current.members().contains(original) && !current.members()
                                                                                         .contains(replacement) && ready.contains(replacement);
    }

    private static List<NodeId> swapped(List<NodeId> voters, NodeId original, NodeId replacement) {
        return voters.stream()
                     .map(node -> node.equals(original)
                                  ? replacement
                                  : node)
                     .toList();
    }

    private static int rank(NodeId node, NodeId self, Set<NodeId> existing, Set<NodeId> ready) {
        if (node.equals(self)) {
            return 0;
        }

        if (ready.contains(node)) {
            return existing.contains(node)
                   ? 1
                   : 2;
        }

        return 3;
    }
}
