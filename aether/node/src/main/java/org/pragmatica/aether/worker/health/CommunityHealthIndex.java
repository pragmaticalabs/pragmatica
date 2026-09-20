// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.MemberHealth;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Report;


/// Ephemeral, explicitly governor-observed positive evidence. Missing or stale evidence makes a
/// worker unavailable for placement; it cannot authorize membership removal or provider deletion.
/// A challenge bounds network delay and prevents an old report renewing freshness after restart.
public final class CommunityHealthIndex {
    private final NodeId self;
    private final Function<String, Option<GovernorAnnouncementValue>> authority;
    private final Function<NodeId, Option<String>> assignment;
    private final TimeSource clock;
    private final org.pragmatica.lang.io.TimeSpan freshness;
    private final int maximumMembers;
    private final String incarnation = UUID.randomUUID().toString();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Observation> observations = new HashMap<>();
    private final Map<NodeId, Long> incarnations = new HashMap<>();
    private long sequence;

    private record Pending(Request request, NodeId governor, long sentAt) {}

    private record Observation(Report report,
                               long receivedAt,
                               org.pragmatica.lang.io.TimeSpan transit,
                               Map<NodeId, MemberHealth> members) {}

    private CommunityHealthIndex(NodeId self,
                                 Function<String, Option<GovernorAnnouncementValue>> authority,
                                 Function<NodeId, Option<String>> assignment,
                                 TimeSource clock,
                                 org.pragmatica.lang.io.TimeSpan freshness,
                                 int maximumMembers) {
        this.self = self;
        this.authority = authority;
        this.assignment = assignment;
        this.clock = clock;
        this.freshness = freshness;
        this.maximumMembers = maximumMembers;
    }

    public static CommunityHealthIndex communityHealthIndex(NodeId self,
                                                            Function<String, Option<GovernorAnnouncementValue>> authority,
                                                            Function<NodeId, Option<String>> assignment,
                                                            TimeSource clock,
                                                            org.pragmatica.lang.io.TimeSpan freshness,
                                                            int maximumMembers) {
        return new CommunityHealthIndex(self, authority, assignment, clock, freshness, maximumMembers);
    }

    /// At most one outstanding challenge per community. Polling faster than its timeout does not
    /// invalidate an in-flight response; a silent governor is retried after the freshness window.
    public synchronized Option<Request> request(String community) {
        var existing = Option.option(pending.get(community));

        if (existing.filter(value -> clock.nanoTime() - value.sentAt() < freshness.nanos()
                                     && authority.apply(community)
                                                 .filter(current -> !current.dissolved()
                                                                    && current.governorId()
                                                                              .equals(value.governor())
                                                                    && current.communityTerm() == value.request()
                                                                                                       .governorTerm())
                                                 .isPresent())
                    .isPresent()) {
            return Option.none();
        }

        return authority.apply(community)
                        .filter(value -> !value.dissolved())
                        .map(value -> {
                                 var request = new Request(self,
                                                           community,
                                                           value.communityTerm(),
                                                           incarnation,
                                                           ++sequence);

                                 pending.put(community,
                                             new Pending(request,
                                                         value.governorId(),
                                                         clock.nanoTime()));

                                 return request;
                             });
    }

    /// The transport must bind report.sender to the authenticated/admitted peer identity.
    public synchronized boolean accept(NodeId transportSender, Report report) {
        if (!transportSender.equals(report.sender()) || report.members().size() > maximumMembers) {
            return false;
        }

        var challenge = Option.option(pending.get(report.communityId()));

        if (challenge.filter(value -> matches(value, report)).isEmpty() || !currentAuthority(report)) {
            return false;
        }

        if (!validMembers(report)) {
            return false;
        }

        var request = pending.remove(report.communityId());
        long now = clock.nanoTime();
        var members = new HashMap<NodeId, MemberHealth>();

        report.members()
              .forEach(value -> {
                           members.put(value.node(),
                                       value);
                           incarnations.merge(value.node(),
                                              value.incarnation(),
                                              Math::max);
                       });
        observations.put(report.communityId(),
                         new Observation(report,
                                         now,
                                         org.pragmatica.lang.io.TimeSpan.timeSpan(now - request.sentAt()).nanos(),
                                         Map.copyOf(members)));

        return true;
    }

    private boolean matches(Pending pending, Report report) {
        long elapsed = clock.nanoTime() - pending.sentAt();

        return pending.governor()
                      .equals(report.sender())
               && pending.request()
                         .governorTerm() == report.governorTerm()
               && pending.request()
                         .incarnation()
                         .equals(report.incarnation())
               && pending.request()
                         .sequence() == report.sequence()
               && elapsed >= 0
               && elapsed < freshness.nanos();
    }

    /// A valid current-term response proves the governor reporting path, independently of member readiness.
    public synchronized boolean hasFreshReport(String community) {
        return Option.option(observations.get(community))
                     .filter(value -> currentAuthority(value.report())
                                      && clock.nanoTime() - value.receivedAt() >= 0
                                      && clock.nanoTime() - value.receivedAt() < freshness.nanos())
                     .isPresent();
    }

    private boolean currentAuthority(Report report) {
        return authority.apply(report.communityId())
                        .filter(value -> !value.dissolved()
                                         && value.governorId()
                                                 .equals(report.sender())
                                         && value.communityTerm() == report.governorTerm())
                        .isPresent()
               && assignment.apply(report.sender())
                            .filter(report.communityId()::equals)
                            .isPresent();
    }

    private boolean validMembers(Report report) {
        var seen = new HashSet<NodeId>();

        return report.members()
                     .stream()
                     .allMatch(member -> member.incarnation() >= 0
                                         && member.observationAge()
                                                  .nanos() >= 0
                                         && member.incarnation() >= incarnations.getOrDefault(member.node(),
                                                                                              0L)
                                         && seen.add(member.node())
                                         && assignment.apply(member.node())
                                                      .filter(report.communityId()::equals)
                                                      .isPresent());
    }

    /// Provenance travels with any positive membership tap; consumers must never reinterpret
    /// absence from this list as a death report.
    public record GovernorEvidence(String community, NodeId governor, long governorTerm, MemberHealth member) {}

    public synchronized java.util.List<GovernorEvidence> positiveEvidence(String community) {
        return Option.option(observations.get(community))
                     .filter(value -> currentAuthority(value.report()))
                     .map(value -> value.members()
                                        .values()
                                        .stream()
                                        .filter(MemberHealth::alive)
                                        .filter(member -> assignment.apply(member.node())
                                                                    .filter(community::equals)
                                                                    .isPresent() && fresh(value, member))
                                        .map(member -> new GovernorEvidence(community,
                                                                            value.report().sender(),
                                                                            value.report().governorTerm(),
                                                                            member))
                                        .toList())
                     .or(java.util.List.of());
    }

    public synchronized boolean isReachable(NodeId node) {
        return health(node).filter(MemberHealth::alive)
                     .isPresent();
    }

    /// Current positive readiness only; absence is unknown, never an inferred failure or READY.
    public synchronized java.util.Set<NodeId> readyMembers() {
        return observations.values()
                           .stream()
                           .flatMap(observation -> observation.members()
                                                              .keySet()
                                                              .stream())
                           .filter(this::isReady)
                           .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public synchronized boolean isReady(NodeId node) {
        return health(node).filter(value -> value.alive() && value.ready())
                     .isPresent();
    }

    private Option<MemberHealth> health(NodeId node) {
        return assignment.apply(node)
                         .flatMap(community -> Option.option(observations.get(community)))
                         .filter(value -> currentAuthority(value.report()))
                         .flatMap(value -> Option.option(value.members().get(node)).filter(member -> fresh(value, member)));
    }

    private boolean fresh(Observation observation, MemberHealth member) {
        long elapsed = clock.nanoTime() - observation.receivedAt();

        return elapsed >= 0
               && elapsed < freshness.nanos()
               && observation.transit()
                             .nanos() < freshness.nanos() - elapsed
               && member.observationAge()
                        .nanos() < freshness.nanos() - elapsed - observation.transit()
                                                                            .nanos();
    }

    /// Remove retired community state without retaining one map entry per historic community.
    public synchronized org.pragmatica.lang.Unit retainCommunities(java.util.Set<String> communities) {
        pending.keySet().retainAll(communities);
        observations.keySet().retainAll(communities);
        incarnations.keySet().removeIf(node -> assignment.apply(node)
                                                         .filter(communities::contains)
                                                         .isEmpty());

        return org.pragmatica.lang.Unit.unit();
    }
}
