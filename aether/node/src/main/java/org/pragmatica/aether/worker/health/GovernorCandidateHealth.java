// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Bounded direct admission probes break the initial governor/report dependency. A nomination
/// creates no authority and does not itself count as liveness. Only a fresh direct READY pong does.
public final class GovernorCandidateHealth {
    private record Pending(String community, long sentAt) {}

    private record Proof(String community,
                         MetricObservation observation,
                         long receivedAt,
                         org.pragmatica.lang.io.TimeSpan initialAge) {}

    private final Function<NodeId, Option<String>> assignment;
    private final TimeSource clock;
    private final org.pragmatica.lang.io.TimeSpan freshness;
    private final int maximumPending;
    private final Map<NodeId, Pending> pending = new HashMap<>();
    private final Map<NodeId, Proof> proofs = new HashMap<>();

    private GovernorCandidateHealth(Function<NodeId, Option<String>> assignment,
                                    TimeSource clock,
                                    org.pragmatica.lang.io.TimeSpan freshness,
                                    int maximumPending) {
        this.assignment = assignment;
        this.clock = clock;
        this.freshness = freshness;
        this.maximumPending = maximumPending;
    }

    public static GovernorCandidateHealth governorCandidateHealth(Function<NodeId, Option<String>> assignment,
                                                                  TimeSource clock,
                                                                  org.pragmatica.lang.io.TimeSpan freshness,
                                                                  int maximumPending) {
        return new GovernorCandidateHealth(assignment, clock, freshness, maximumPending);
    }

    /// True asks the caller to send a direct ping. Repeated nominations cannot create probe storms.
    public synchronized boolean request(NodeId node, String community) {
        prune();
        if (assignment.apply(node).filter(community::equals).isEmpty() || pending.containsKey(node) || isEligible(node) || pending.size() >= maximumPending) {
            return false;
        }

        pending.put(node, new Pending(community, clock.nanoTime()));

        return true;
    }

    public synchronized boolean isPending(NodeId node) {
        return Option.option(pending.get(node))
                     .filter(value -> fresh(value.sentAt()) && assignment.apply(node)
                                                                         .filter(value.community()::equals)
                                                                         .isPresent())
                     .isPresent();
    }

    public synchronized boolean recordPong(NodeId node, String lifecycle, MetricObservation observation) {
        var request = Option.option(pending.get(node));

        if (!"READY".equals(lifecycle) || observation.incarnation() < 0 || observation.sequence() < 0 || !MetricObservation.isTimestampFresh(observation.observedAtMs(),
                                                                                                                                             System.currentTimeMillis()) || request.filter(value -> fresh(value.sentAt()) && assignment.apply(node)
                                                                                                                                                                                                                                       .filter(value.community()::equals)
                                                                                                                                                                                                                                       .isPresent())
                                                                                                                                                                                   .isEmpty() || Option.option(proofs.get(node))
                                                                                                                                                                                                       .filter(value -> !observation.isAfter(value.observation()))
                                                                                                                                                                                                       .isPresent()) {
            return false;
        }

        var accepted = pending.remove(node);

        proofs.put(node,
                   new Proof(accepted.community(),
                             observation,
                             clock.nanoTime(),
                             org.pragmatica.lang.io.TimeSpan.timeSpan(Math.max(0,
                                                                               System.currentTimeMillis() - observation.observedAtMs()))
                                                            .millis()));

        return true;
    }

    public synchronized boolean isEligible(NodeId node) {
        return Option.option(proofs.get(node))
                     .filter(value -> fresh(value.receivedAt())
                                      && value.initialAge()
                                              .nanos() < freshness.nanos() - (clock.nanoTime() - value.receivedAt())
                                      && MetricObservation.isTimestampFresh(value.observation().observedAtMs(),
                                                                            System.currentTimeMillis())
                                      && assignment.apply(node)
                                                   .filter(value.community()::equals)
                                                   .isPresent())
                     .isPresent();
    }

    private boolean fresh(long at) {
        long elapsed = clock.nanoTime() - at;

        return elapsed >= 0 && elapsed < freshness.nanos();
    }

    private void prune() {
        pending.entrySet()
               .removeIf(entry -> !fresh(entry.getValue().sentAt()) || assignment.apply(entry.getKey())
                                                                                 .filter(entry.getValue()
                                                                                              .community()::equals)
                                                                                 .isEmpty());
        proofs.entrySet()
              .removeIf(entry -> assignment.apply(entry.getKey())
                                           .filter(entry.getValue().community()::equals)
                                           .isEmpty());
    }
}
