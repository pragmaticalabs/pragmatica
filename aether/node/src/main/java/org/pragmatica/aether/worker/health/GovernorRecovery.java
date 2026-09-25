// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.worker.governor.GovernorAuthority;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Core-owned reporting-path recovery. Losing a report never declares any community member dead.
/// Each community has one cursor and at most one in-flight grant; probes share the bounded candidate cache.
public final class GovernorRecovery {
    private record Attempt(long term, long missingSince, long lastProbe, int cursor, boolean committing) {}

    private final CommunityMemberDirectory directory;
    private final CommunityHealthIndex reports;
    private final GovernorCandidateHealth candidates;
    private final Function<String, Option<GovernorAnnouncementValue>> authority;
    private final GovernorAuthority grants;
    private final BooleanSupplier leader;
    private final Function<NodeId, Option<String>> address;
    private final Consumer<NodeId> probe;
    private final TimeSource clock;
    private final TimeSpan absence;
    private final TimeSpan probeInterval;
    private final Map<String, Attempt> attempts = new HashMap<>();

    private GovernorRecovery(CommunityMemberDirectory directory,
                             CommunityHealthIndex reports,
                             GovernorCandidateHealth candidates,
                             Function<String, Option<GovernorAnnouncementValue>> authority,
                             GovernorAuthority grants,
                             BooleanSupplier leader,
                             Function<NodeId, Option<String>> address,
                             Consumer<NodeId> probe,
                             TimeSource clock,
                             TimeSpan absence,
                             TimeSpan probeInterval) {
        this.directory = directory;
        this.reports = reports;
        this.candidates = candidates;
        this.authority = authority;
        this.grants = grants;
        this.leader = leader;
        this.address = address;
        this.probe = probe;
        this.clock = clock;
        this.absence = absence;
        this.probeInterval = probeInterval;
    }

    public static GovernorRecovery governorRecovery(CommunityMemberDirectory directory,
                                                    CommunityHealthIndex reports,
                                                    GovernorCandidateHealth candidates,
                                                    Function<String, Option<GovernorAnnouncementValue>> authority,
                                                    GovernorAuthority grants,
                                                    BooleanSupplier leader,
                                                    Function<NodeId, Option<String>> address,
                                                    Consumer<NodeId> probe,
                                                    TimeSource clock,
                                                    TimeSpan absence,
                                                    TimeSpan probeInterval) {
        return new GovernorRecovery(directory,
                                    reports,
                                    candidates,
                                    authority,
                                    grants,
                                    leader,
                                    address,
                                    probe,
                                    clock,
                                    absence,
                                    probeInterval);
    }

    public synchronized Unit poll() {
        if (!leader.getAsBoolean()) {
            attempts.clear();

            return Unit.unit();
        }

        var communities = directory.communities();

        attempts.keySet().retainAll(communities);
        communities.stream().sorted().forEach(this::reconcile);

        return Unit.unit();
    }

    private void reconcile(String community) {
        var current = authority.apply(community);
        long term = current.map(GovernorAnnouncementValue::communityTerm).or(0L);
        long now = clock.nanoTime();
        var attempt = attempts.computeIfAbsent(community,
                                               _ -> new Attempt(term, now, now - probeInterval.nanos(), 0, false));

        if (attempt.term() != term || reports.hasFreshReport(community)) {
            attempts.put(community, new Attempt(term, now, now - probeInterval.nanos(), 0, false));

            return;
        }

        if (attempt.committing() || (current.isPresent() && now - attempt.missingSince() < absence.nanos())) {
            return;
        }

        var members = directory.members(community)
                               .stream()
                               .sorted()
                               .filter(node -> current.filter(value -> value.governorId()
                                                                            .equals(node))
                                                      .isEmpty())
                               .toList();

        if (members.isEmpty()) {
            return;
        }

        var ready = members.stream()
                           .filter(candidates::isEligible)
                           .filter(node -> address.apply(node)
                                                  .isPresent())
                           .findFirst();

        if (ready.isPresent()) {
            var candidate = ready.get();

            attempts.put(community,
                         new Attempt(term, attempt.missingSince(), attempt.lastProbe(), attempt.cursor(), true));
            grants.reconcile(community,
                             candidate,
                             term,
                             address.apply(candidate).or(""))
                  .onResult(_ -> grantFinished(community, term));

            return;
        }

        if (now - attempt.lastProbe() < probeInterval.nanos()) {
            return;
        }

        var candidate = members.get(Math.floorMod(attempt.cursor(), members.size()));

        attempts.put(community,
                     new Attempt(term, attempt.missingSince(), now, attempt.cursor() + 1, false));
        if (candidates.request(candidate, community)) {
            probe.accept(candidate);
        }
    }

    private synchronized void grantFinished(String community, long term) {
        Option.option(attempts.get(community))
              .filter(value -> value.term() == term)
              .onPresent(value -> attempts.put(community,
                                               new Attempt(term,
                                                           value.missingSince(),
                                                           value.lastProbe(),
                                                           value.cursor(),
                                                           false)));
    }
}
