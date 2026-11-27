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
/// becomes DEGRADED below it. The floor is the COMMITTED `viability_floor` (R-CF.18, #241; `CommunityPolicies`). The teardown states and an undecodable state never change here (#964).
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

    private static ClusterModel evaluate(ClusterModel m, Ctx ctx, String id, CommunityValue community) {
        var floor = CommunityPolicies.current(m, ctx).viabilityFloor();
        var next = next(community.state(), liveMembers(m, id), floor);

        if (next != community.state()) {
            var key = new CommunityKey(id);
            var announcement = new GovernorAnnouncementKey(id);
            var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                          Option.some((AetherValue) community),
                                                                          Option.some((AetherValue) community.withState(next)));
            var guard = new KVCommand.ReadWitness<AetherKey>(announcement,
                                                             m.rosters.get(id).map(v -> (Object) v));
            var window = openWindow(m, ctx, id, community.state(), next);

            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.COMMUNITY,
                                        key,
                                        List.of(mutation))
                                 .withGuards(List.of(guard))
                                 .withWindow(window.write()));
        } else {
            m.activationDeadline = m.activationDeadline.remove(id);
        }

        return m;
    }

    /// #3815, #3852: the in-flight window belongs to ONE write: the community's change from `from` to `to`. It opens at the first draft of that
    /// edge and lasts the submission-expiry bound; a redraft of the same edge (every pass redrafts while the state differs) keeps it where it
    /// is, so a write that is lost again and again ends the window at the first bound. A draft for another edge (an earlier write's window
    /// still in the slot, e.g. a commit coalesced with the next draft so that no pass saw agreement) opens its own, with a new write number.
    /// `Placement` holds the empty-audience event back only inside the window of the community's ACTIVE write.
    private static ActivationWindow openWindow(ClusterModel m,
                                               Ctx ctx,
                                               String id,
                                               CommunityState from,
                                               CommunityState to) {
        var window = m.activationDeadline.get(id)
                                         .filter(current -> current.isFor(from, to))
                                         .or(() -> opened(m, ctx, from, to));

        m.activationDeadline = m.activationDeadline.put(id, window);

        return window;
    }

    private static ActivationWindow opened(ClusterModel m, Ctx ctx, CommunityState from, CommunityState to) {
        m.activationWrites = m.activationWrites + 1;

        return ActivationWindow.activationWindow(m.activationWrites,
                                                 from,
                                                 to,
                                                 ctx.monoNs() + ctx.settings().submissionExpiry() * 1_000_000L);
    }

    /// #3815, #3852: a refused or failed write is not in flight: the window it opened closes at the settlement. Only that window: a submission
    /// closes a window when its draft carries the window's write number, so the late settlement of an earlier or different write leaves the
    /// current write's window alone (no-op for a draft with no window, a community whose window is not open, or one already agreed). The
    /// redraft of the next pass does not reopen it.
    static ClusterModel closeWindows(ClusterModel m, Ctx ctx, List<TxDraft> drafts) {
        drafts.stream()
              .filter(draft -> draft.kind() == SubmitKind.COMMUNITY)
              .forEach(draft -> closeWindow(m, ctx, draft));

        return m;
    }

    private static ClusterModel closeWindow(ClusterModel m, Ctx ctx, TxDraft draft) {
        if (draft.key() instanceof CommunityKey key) {
            m.activationDeadline = m.activationDeadline.get(key.communityId())
                                                       .filter(window -> window.write() == draft.window())
                                                       .map(window -> m.activationDeadline.put(key.communityId(),
                                                                                               window.closedAt(ctx.monoNs())))
                                                       .or(m.activationDeadline);
        }

        return m;
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

    /// The count the state in [#evaluate] was decided on, as the management API reads it (#3159): [#liveMembers] of the model `state` holds,
    /// when it is built and the community has a committed, non-dissolved roster. Anything else (Dormant, Rebuilding, no roster) is
    /// [Option#none] — "nothing to count", not "zero members alive".
    static Option<Integer> observed(ClusterState state, String id) {
        return state instanceof ClusterState.Active active
               ? active.model().rosters
                       .get(id)
                       .filter(roster -> !roster.dissolved())
                       .map(_ -> liveMembers(active.model(),
                                             id))
               : Option.none();
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
