/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Which partitions this node holds as a replica WITHOUT yet being authoritative for them (#1777 track 2).
///
/// A partition enters the set when the node becomes its replica through a ring change, or at boot, and
/// leaves it only when anti-entropy has filled it from an authoritative source ([DHTAntiEntropy]). While
/// pending, the node's "absent" is no evidence of absence. For each pending partition the set remembers
/// the PREVIOUS replica set as of the change that made this node a replica: those holders may still be
/// ring members holding the data, so they are catch-up sources alongside the current co-replicas. A
/// partition the node stops owning while pending leaves the set (rounds run only for owned partitions, so
/// it would otherwise answer CATCHING_UP forever). Each pending partition also counts the catch-up rounds
/// started for it, so a partition that cannot complete is visible ([#stuck]).
///
/// In memory by design: the store is in memory too, so a restarted node is empty and must start pending.
final class CatchUpState {
    /// A pending partition: the previous holders recorded for it and the catch-up rounds started so far.
    /// `sinceBoot` marks a partition pending since this node booted, whose previous holders are not known
    /// exactly and are read off the current ring instead (see [DHTNode#previousHolders]).
    private record Pending(Set<NodeId> previousHolders, int rounds, boolean sinceBoot) {
        Pending merge(Pending other) {
            return new Pending(union(previousHolders, other.previousHolders), rounds, sinceBoot || other.sinceBoot);
        }

        Pending nextRound() {
            return new Pending(previousHolders, rounds + 1, sinceBoot);
        }
    }

    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();

    private CatchUpState() {}

    static CatchUpState catchUpState() {
        return new CatchUpState();
    }

    /// Mark `partition` catching up, adding `previousHolders` to the sources already recorded for it.
    @Contract
    void markCatchingUp(Partition partition, Collection<NodeId> previousHolders) {
        pending.merge(partition.value(), new Pending(Set.copyOf(previousHolders), 0, false), Pending::merge);
    }

    /// Mark `partition` catching up since boot: no previous holders are recorded, they are walked live.
    @Contract
    void markCatchingUpSinceBoot(Partition partition) {
        pending.merge(partition.value(), new Pending(Set.of(), 0, true), Pending::merge);
    }

    boolean pendingSinceBoot(Partition partition) {
        return Option.option(pending.get(partition.value()))
                     .map(Pending::sinceBoot)
                     .or(false);
    }

    /// Mark `partition` serving: the node is authoritative for it from now on.
    @Contract
    void markServing(Partition partition) {
        pending.remove(partition.value());
    }

    Readiness readiness(Partition partition) {
        return pending.containsKey(partition.value())
               ? Readiness.CATCHING_UP
               : Readiness.SERVING;
    }

    /// The previous holders recorded for a pending partition; empty when it is serving.
    Set<NodeId> previousHolders(Partition partition) {
        return Option.option(pending.get(partition.value()))
                     .map(Pending::previousHolders)
                     .or(Set.of());
    }

    /// Count one more catch-up round started for `partition`; returns the count, or 0 when it is serving.
    int noteRound(Partition partition) {
        return Option.option(pending.computeIfPresent(partition.value(), (_, entry) -> entry.nextRound()))
                     .map(Pending::rounds)
                     .or(0);
    }

    /// Pending partitions that have started at least `rounds` catch-up rounds without completing.
    int stuck(int rounds) {
        return (int) pending.values()
                            .stream()
                            .filter(entry -> entry.rounds() >= rounds)
                            .count();
    }

    List<Partition> pendingPartitions() {
        return pending.keySet()
                      .stream()
                      .sorted()
                      .map(Partition::at)
                      .toList();
    }

    private static Set<NodeId> union(Set<NodeId> left, Set<NodeId> right) {
        var merged = new HashSet<>(left);

        merged.addAll(right);

        return Set.copyOf(merged);
    }
}
