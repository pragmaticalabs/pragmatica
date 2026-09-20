// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.MemberHealth;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Report;


/// Governor-local direct pong observations, separate from leader-only metric aggregation. A node
/// must belong to this governor's committed community; relay metrics and SWIM gossip cannot renew it.
public final class CommunityHealthReporter {
    private record Direct(long incarnation,
                          long sequence,
                          boolean ready,
                          long receivedAt,
                          org.pragmatica.lang.io.TimeSpan initialAge) {}

    private final NodeId self;
    private final Function<String, Option<GovernorAnnouncementValue>> authority;
    private final Function<NodeId, Option<String>> assignment;
    private final Predicate<NodeId> core;
    private final TimeSource clock;
    private final org.pragmatica.lang.io.TimeSpan freshness;
    private final Map<NodeId, Direct> direct = new HashMap<>();

    private CommunityHealthReporter(NodeId self,
                                    Function<String, Option<GovernorAnnouncementValue>> authority,
                                    Function<NodeId, Option<String>> assignment,
                                    Predicate<NodeId> core,
                                    TimeSource clock,
                                    org.pragmatica.lang.io.TimeSpan freshness) {
        this.self = self;
        this.authority = authority;
        this.assignment = assignment;
        this.core = core;
        this.clock = clock;
        this.freshness = freshness;
    }

    public static CommunityHealthReporter communityHealthReporter(NodeId self,
                                                                  Function<String, Option<GovernorAnnouncementValue>> authority,
                                                                  Function<NodeId, Option<String>> assignment,
                                                                  Predicate<NodeId> core,
                                                                  TimeSource clock,
                                                                  org.pragmatica.lang.io.TimeSpan freshness) {
        return new CommunityHealthReporter(self, authority, assignment, core, clock, freshness);
    }

    /// Invoke only for a direct pong from this authenticated sender. Record the governor's own local
    /// lifecycle on each report tick with this same method; it does not send a network ping to itself.
    public synchronized org.pragmatica.lang.Unit recordPong(NodeId sender,
                                                            String lifecycleState,
                                                            org.pragmatica.cluster.metrics.MetricObservation observation) {
        long nowMillis = System.currentTimeMillis();

        if (!org.pragmatica.cluster.metrics.MetricObservation.isTimestampFresh(observation.observedAtMs(), nowMillis)) {
            return org.pragmatica.lang.Unit.unit();
        }

        record(sender,
               lifecycleState,
               observation.incarnation(),
               observation.sequence(),
               org.pragmatica.lang.io.TimeSpan.timeSpan(Math.max(0, nowMillis - observation.observedAtMs())).millis());

        return org.pragmatica.lang.Unit.unit();
    }

    public synchronized org.pragmatica.lang.Unit recordSelf(String lifecycleState, long incarnation) {
        long sequence = Option.option(direct.get(self)).map(value -> value.sequence() + 1).or(1L);

        record(self,
               lifecycleState,
               incarnation,
               sequence,
               org.pragmatica.lang.io.TimeSpan.timeSpan(0).nanos());

        return org.pragmatica.lang.Unit.unit();
    }

    private void record(NodeId sender,
                        String lifecycleState,
                        long incarnation,
                        long sequence,
                        org.pragmatica.lang.io.TimeSpan initialAge) {
        var community = assignment.apply(self);

        if (incarnation < 0 || sequence < 0 || community.isEmpty() || assignment.apply(sender)
                                                                                .filter(value -> community.filter(value::equals)
                                                                                                          .isPresent())
                                                                                .isEmpty()) {
            return;
        }

        var previous = Option.option(direct.get(sender));

        if (previous.filter(value -> value.incarnation() > incarnation || value.incarnation() == incarnation && value.sequence() >= sequence)
                    .isPresent()) {
            return;
        }

        direct.put(sender,
                   new Direct(incarnation, sequence, "READY".equals(lifecycleState), clock.nanoTime(), initialAge));
    }

    public synchronized Option<Report> respond(NodeId transportSender, Request request) {
        if (!transportSender.equals(request.sender()) || !core.test(transportSender)) {
            return Option.none();
        }

        return authority.apply(request.communityId())
                        .filter(value -> !value.dissolved()
                                         && value.governorId()
                                                 .equals(self)
                                         && value.communityTerm() == request.governorTerm())
                        .filter(_ -> assignment.apply(self)
                                               .filter(request.communityId()::equals)
                                               .isPresent())
                        .map(_ -> report(request));
    }

    private Report report(Request request) {
        direct.keySet().removeIf(node -> assignment.apply(node)
                                                   .filter(request.communityId()::equals)
                                                   .isEmpty());
        long now = clock.nanoTime();
        var members = direct.entrySet()
                            .stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> {
                                     var age = now - entry.getValue()
                                                          .receivedAt() + entry.getValue()
                                                                               .initialAge()
                                                                               .nanos();
                                     boolean alive = age >= 0 && age < freshness.nanos();

                                     return new MemberHealth(entry.getKey(),
                                                             entry.getValue().incarnation(),
                                                             alive,
                                                             alive && entry.getValue().ready(),
                                                             TimeSpan.timeSpan(Math.max(0, age)).nanos());
                                 })
                            .toList();

        return new Report(self,
                          request.communityId(),
                          request.governorTerm(),
                          request.incarnation(),
                          request.sequence(),
                          members);
    }
}
