// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Bounded pre-assignment direct evidence. Admission intent is supplied independently of peer labels.
/// READY is not required: activation itself depends on the resulting committed community assignment.
public final class WorkerAdmission {
    private final Predicate<NodeId> authorized;
    private final Consumer<NodeId> probe;
    private final BiConsumer<NodeId, Long> accept;
    private final TimeSource clock;
    private final TimeSpan timeout;
    private final int maximumPending;
    private int cursor;
    private final Map<NodeId, Long> pending = new HashMap<>();
    private final Map<NodeId, MetricObservation> accepted = new HashMap<>();

    private WorkerAdmission(Predicate<NodeId> authorized,
                            Consumer<NodeId> probe,
                            BiConsumer<NodeId, Long> accept,
                            TimeSource clock,
                            TimeSpan timeout,
                            int maximumPending) {
        this.authorized = authorized;
        this.probe = probe;
        this.accept = accept;
        this.clock = clock;
        this.timeout = timeout;
        this.maximumPending = maximumPending;
    }

    public static WorkerAdmission workerAdmission(Predicate<NodeId> authorized,
                                                  Consumer<NodeId> probe,
                                                  BiConsumer<NodeId, Long> accept,
                                                  TimeSource clock,
                                                  TimeSpan timeout,
                                                  int maximumPending) {
        return new WorkerAdmission(authorized, probe, accept, clock, timeout, maximumPending);
    }

    /// Preserve the cursor when capacity is exhausted, so silent early identities cannot starve
    /// later admitted joiners after their pending probes expire.
    public synchronized Unit poll(java.util.List<NodeId> nodes) {
        prune();
        if (nodes.isEmpty()) {
            cursor = 0;

            return Unit.unit();
        }

        int visited = 0;

        while (visited < nodes.size() && pending.size() + accepted.size() < maximumPending) {
            var node = nodes.get(Math.floorMod(cursor, nodes.size()));

            cursor = Math.floorMod(cursor + 1, nodes.size());
            visited++;
            request(node);
        }

        return Unit.unit();
    }

    private void prune() {
        long now = clock.nanoTime();

        pending.entrySet()
               .removeIf(entry -> now - entry.getValue() >= timeout.nanos() || !authorized.test(entry.getKey()));
        accepted.entrySet()
                .removeIf(entry -> !authorized.test(entry.getKey()) || !MetricObservation.isTimestampFresh(entry.getValue()
                                                                                                                .observedAtMs(),
                                                                                                           System.currentTimeMillis()));
    }

    public synchronized Unit request(NodeId node) {
        long now = clock.nanoTime();

        pending.entrySet()
               .removeIf(entry -> now - entry.getValue() >= timeout.nanos() || !authorized.test(entry.getKey()));
        accepted.entrySet()
                .removeIf(entry -> !authorized.test(entry.getKey()) || !MetricObservation.isTimestampFresh(entry.getValue()
                                                                                                                .observedAtMs(),
                                                                                                           System.currentTimeMillis()));
        if (authorized.test(node) && !pending.containsKey(node) && pending.size() + accepted.size() < maximumPending) {
            pending.put(node, now);
            probe.accept(node);
        }

        return Unit.unit();
    }

    public synchronized boolean isPending(NodeId node) {
        return org.pragmatica.lang.Option.option(pending.get(node))
                                         .filter(sent -> authorized.test(node)
                                                         && clock.nanoTime() - sent >= 0
                                                         && clock.nanoTime() - sent < timeout.nanos())
                                         .isPresent();
    }

    public synchronized boolean recordPong(NodeId node,
                                           String state,
                                           long membershipIncarnation,
                                           MetricObservation observation) {
        var sent = org.pragmatica.lang.Option.option(pending.get(node));

        if (sent.filter(value -> authorized.test(node)
                                 && clock.nanoTime() - value >= 0
                                 && clock.nanoTime() - value < timeout.nanos())
                .isEmpty() || membershipIncarnation < 0 || observation.incarnation() < 0 || observation.sequence() < 0 || !MetricObservation.isTimestampFresh(observation.observedAtMs(),
                                                                                                                                                              System.currentTimeMillis()) || org.pragmatica.lang.Option.option(accepted.get(node))
                                                                                                                                                                                                                       .filter(previous -> !observation.isAfter(previous))
                                                                                                                                                                                                                       .isPresent() || !("SYNCING".equals(state) || "READY".equals(state))) {
            return false;
        }

        pending.remove(node);
        accepted.put(node, observation);
        accept.accept(node, membershipIncarnation);

        return true;
    }
}
