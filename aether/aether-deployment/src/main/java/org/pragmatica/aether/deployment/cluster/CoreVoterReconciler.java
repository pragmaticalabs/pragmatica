// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
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


/// Turns admitted, synchronized CORE candidates into a concrete consensus handoff.
/// Capacity intent cannot change the electorate until enough actual candidates exist.
public interface CoreVoterReconciler {
    Promise<Unit> reconcile();

    static CoreVoterReconciler coreVoterReconciler(NodeId self,
                                                   BooleanSupplier leader,
                                                   Supplier<Option<VoterConfiguration>> installed,
                                                   Supplier<Option<VoterConfiguration>> retirementSafe,
                                                   IntSupplier desired,
                                                   Supplier<Set<NodeId>> readyCoreCandidates,
                                                   Function<ClusterConfig, Promise<Unit>> handoff) {
        record reconciler(NodeId self,
                          BooleanSupplier leader,
                          Supplier<Option<VoterConfiguration>> installed,
                          Supplier<Option<VoterConfiguration>> retirementSafe,
                          IntSupplier desired,
                          Supplier<Set<NodeId>> readyCoreCandidates,
                          Function<ClusterConfig, Promise<Unit>> handoff,
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
                var target = selectVoters(self, current, readyCoreCandidates.get(), desired.getAsInt());

                if (target.isEmpty() || (retirementSafe.get().filter(current::equals).isPresent() && Set.copyOf(target).equals(Set.copyOf(current.members())))) {
                    return Promise.unitPromise();
                }

                return ClusterConfig.clusterConfig(target)
                                    .async()
                                    .flatMap(handoff::apply);
            }
        }

        return new reconciler(self,
                              leader,
                              installed,
                              retirementSafe,
                              desired,
                              readyCoreCandidates,
                              handoff,
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
