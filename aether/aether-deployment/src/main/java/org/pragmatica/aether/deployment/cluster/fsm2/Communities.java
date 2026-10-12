// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// Per-community state (reconcile step 5, B-074): the leader walks every committed community, recomputes its state from
/// observed live membership, and writes exactly the edges. FORMING and DEGRADED become ACTIVE at the viability floor; ACTIVE
/// becomes DEGRADED below it. The teardown states and an undecodable state never change here (#964).
public sealed interface Communities {
    static ClusterModel run(ClusterModel m, Ctx ctx) {
        m.communities.entries()
                     .stream()
                     .sorted(Map.Entry.comparingByKey())
                     .forEach(entry -> evaluate(m,
                                                ctx,
                                                entry.getKey(),
                                                entry.getValue()));

        return m;
    }

    private static void evaluate(ClusterModel m, Ctx ctx, String id, CommunityValue community) {
        var floor = ctx.settings().communityViabilityFloor();
        var next = next(community.state(), liveMembers(m, id), floor);

        if (next != community.state()) {
            var key = new CommunityKey(id);
            var announcement = new GovernorAnnouncementKey(id);
            var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                          Option.some((AetherValue) community),
                                                                          Option.some((AetherValue) community.withState(next)));
            var guard = new KVCommand.ReadWitness<AetherKey>(announcement,
                                                             m.rosters.get(id).map(v -> (Object) v));

            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.COMMUNITY, key, List.of(mutation)).withGuards(List.of(guard)));
        }
    }

    /// Members still on the committed, non-dissolved roster, still directed to this community and counted live. No roster
    /// counts as zero.
    static int liveMembers(ClusterModel m, String id) {
        return m.rosters.get(id)
                        .filter(roster -> !roster.dissolved())
                        .map(roster -> (int) roster.members()
                                                   .stream()
                                                   .distinct()
                                                   .filter(node -> m.directives.get(node)
                                                                               .map(directive -> directive.communityId()
                                                                                                          .equals(id))
                                                                               .or(false))
                                                   .filter(node -> Rows.counts(m, node))
                                                   .count())
                        .or(0);
    }

    static CommunityState next(CommunityState current, int liveMembers, int floor) {
        return switch (current) {
            case FORMING -> liveMembers >= floor
                            ? CommunityState.ACTIVE
                            : CommunityState.FORMING;
            case ACTIVE -> liveMembers < floor
                           ? CommunityState.DEGRADED
                           : CommunityState.ACTIVE;
            case DEGRADED -> liveMembers >= floor
                             ? CommunityState.ACTIVE
                             : CommunityState.DEGRADED;
            case DISSOLVING, DISSOLVED, UNKNOWN -> current;
        };
    }

    record unused() implements Communities {}
}
