// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The LEVEL-triggered half of the departure sweep (#1978, I-36, owner ruling Q19 (a): the sweep removes the references of
/// nodes no longer in committed membership). The event sweep ([KvBackedStreamRegistry#sweepHoldersOf]) runs once per
/// departure edge, so a missed edge or a failed apply would leave a departed node's references for ever. This pass compares
/// the nodes that hold node-owned references with the current membership on every tick, and sweeps a node once it has been
/// absent on two consecutive ticks.
///
/// **Why two ticks.** Membership is a local projection: a leader that just took over, or a worker not yet re-admitted after
/// the leader restarted, is absent from it for a moment while its slices still run. A single absent reading is therefore
/// only a suspicion; absence on two readings one [#INTERVAL] apart is the departure. A node seen again in between is
/// forgotten. The local node is never swept.
///
/// **Who runs it.** The leader only; elsewhere a tick forgets its suspicions and does nothing, so a node that becomes leader
/// starts with a clean slate and sweeps nothing before its second tick. While membership is unknown (the membership FSM is not
/// wired yet) the tick also forgets and does nothing. Every removal is witnessed by the value read, so a sweep racing a node's
/// return and re-acquire is refused for that key.
///
/// **Failure (FER).** A failed sweep apply is logged by the registry and not retried within the tick; the next tick finds the
/// same node still absent and holding, and sweeps again. That repetition is the retry, bounded by the node's keys being gone.
public final class StreamRegistryHolderReconciler {
    /// The cadence of the pass, and the minimum time a node is absent before its references are swept.
    public static final TimeSpan INTERVAL = timeSpan(30).seconds();

    private final KvBackedStreamRegistry registry;
    private final NodeId self;
    private final Supplier<Option<Set<NodeId>>> members;
    private final BooleanSupplier isLeader;
    private Set<NodeId> suspected = Set.of();

    private StreamRegistryHolderReconciler(KvBackedStreamRegistry registry,
                                           NodeId self,
                                           Supplier<Option<Set<NodeId>>> members,
                                           BooleanSupplier isLeader) {
        this.registry = registry;
        this.self = self;
        this.members = members;
        this.isLeader = isLeader;
    }

    /// `members` yields the current membership (every role: cores and workers, live or suspected), or none while it is not
    /// known yet.
    public static StreamRegistryHolderReconciler streamRegistryHolderReconciler(KvBackedStreamRegistry registry,
                                                                                NodeId self,
                                                                                Supplier<Option<Set<NodeId>>> members,
                                                                                BooleanSupplier isLeader) {
        return new StreamRegistryHolderReconciler(registry, self, members, isLeader);
    }

    /// One pass: sweeps the nodes that held references and were absent from membership on this tick and the previous one.
    public synchronized Promise<Unit> tick() {
        if (!isLeader.getAsBoolean()) {
            return forget();
        }

        return members.get()
                      .map(this::reconcile)
                      .or(this::forget);
    }

    private Promise<Unit> forget() {
        suspected = Set.of();

        return Promise.unitPromise();
    }

    private Promise<Unit> reconcile(Set<NodeId> current) {
        var absent = registry.nodeOwnedHolderNodes()
                             .stream()
                             .filter(node -> !current.contains(node) && !self.equals(node))
                             .collect(Collectors.toUnmodifiableSet());
        var departed = absent.stream().filter(suspected::contains).collect(Collectors.toUnmodifiableSet());

        suspected = absent;

        return departed.isEmpty()
               ? Promise.unitPromise()
               : registry.sweepHoldersOf(departed);
    }
}
