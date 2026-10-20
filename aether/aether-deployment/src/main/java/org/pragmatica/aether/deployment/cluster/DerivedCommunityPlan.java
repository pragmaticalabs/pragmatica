// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// What the leader's placement pass does to the DERIVED communities of a cluster (#241): re-stamp the target size when the
/// committed policy changed it, and dissolve a community that has stayed empty. Pure: the same views and clock always give the
/// same actions, so it is tested without a store.
///
/// A declared `[community.<id>]` community is never an input here: only the cluster's own `<source>-w-N` communities are
/// resized or dissolved by policy.
///
/// **Dissolving is two steps, because a joiner's directive and the dissolve are separate commits.** Step one writes DISSOLVING
/// over an empty community; from then on a joiner's commit is refused, because it witnesses the community's value. Step two,
/// on a later pass, reads the committed directives again: still empty, the community becomes DISSOLVED; a directive that
/// committed before step one (the joiner raced it) reopens the community instead. A single write could not tell the two apart,
/// and would dissolve a community that has a member.
public sealed interface DerivedCommunityPlan {
    /// One derived community as the leader reads it: `members` counts every directive that names it, dead or alive, because a
    /// directive outlives its node until termination is confirmed and a community with one is not empty.
    record View(String id, String source, CommunityState state, int targetSize, int members) {
        public static View view(String id, String source, CommunityState state, int targetSize, int members) {
            return new View(id, source, state, targetSize, members);
        }

        boolean isOpen() {
            return state == CommunityState.FORMING || state == CommunityState.ACTIVE || state == CommunityState.DEGRADED;
        }
    }

    sealed interface Action {
        String communityId();

        /// The committed target size differs from the policy's `max_size`.
        record Restamp(String communityId, int targetSize) implements Action {
            public static Restamp restamp(String communityId, int targetSize) {
                return new Restamp(communityId, targetSize);
            }
        }

        /// Step one: an empty community, past the grace, with another community of its source to take its place.
        record BeginDissolve(String communityId) implements Action {
            public static BeginDissolve beginDissolve(String communityId) {
                return new BeginDissolve(communityId);
            }
        }

        /// Step two: the committed directives still name no member.
        record CompleteDissolve(String communityId) implements Action {
            public static CompleteDissolve completeDissolve(String communityId) {
                return new CompleteDissolve(communityId);
            }
        }

        /// Step two, lost race: a directive committed before step one, so the community has a member and stays.
        record Reopen(String communityId) implements Action {
            public static Reopen reopen(String communityId) {
                return new Reopen(communityId);
            }
        }
    }

    /// The communities of `views` that are empty, which is what the caller times: `emptySince` is keyed by these.
    static List<String> emptyOpen(List<View> views) {
        return views.stream()
                    .filter(View::isOpen)
                    .filter(view -> view.members() == 0)
                    .map(View::id)
                    .toList();
    }

    /// At most one action per community, in id order. `emptySince` holds, for each community empty now, the instant it was
    /// first seen empty by this leader; a community missing from it is not yet timed. Growth is read from `policy`: with it
    /// off nothing is dissolved, but a DISSOLVING community that an earlier pass began is still settled.
    static List<Action> plan(List<View> views, CommunityPolicy policy, Map<String, Long> emptySince, long nowMs) {
        return views.stream()
                    .sorted(Comparator.comparing(View::id))
                    .flatMap(view -> actionFor(view, views, policy, emptySince, nowMs).stream())
                    .toList();
    }

    private static Option<Action> actionFor(View view,
                                            List<View> all,
                                            CommunityPolicy policy,
                                            Map<String, Long> emptySince,
                                            long nowMs) {
        return switch (view.state()) {
            case DISSOLVING -> some(settle(view));
            case FORMING, ACTIVE, DEGRADED -> restampOrDissolve(view, all, policy, emptySince, nowMs);
            case DISSOLVED, UNKNOWN -> none();
        };
    }

    private static Action settle(View view) {
        return view.members() == 0
               ? Action.CompleteDissolve.completeDissolve(view.id())
               : Action.Reopen.reopen(view.id());
    }

    private static Option<Action> restampOrDissolve(View view,
                                                    List<View> all,
                                                    CommunityPolicy policy,
                                                    Map<String, Long> emptySince,
                                                    long nowMs) {
        return view.targetSize() != policy.maxSize()
               ? Option.<Action> some(Action.Restamp.restamp(view.id(), policy.maxSize()))
               : dissolveWhenIdle(view, all, policy, emptySince, nowMs);
    }

    private static Option<Action> dissolveWhenIdle(View view,
                                                   List<View> all,
                                                   CommunityPolicy policy,
                                                   Map<String, Long> emptySince,
                                                   long nowMs) {
        return policy.growth() && isIdleLongEnough(view, policy, emptySince, nowMs) && isNewestIdle(view,
                                                                                                    all,
                                                                                                    policy,
                                                                                                    emptySince,
                                                                                                    nowMs) && hasSibling(view,
                                                                                                                         all)
               ? Option.<Action> some(Action.BeginDissolve.beginDissolve(view.id()))
               : Option.<Action> none();
    }

    private static boolean isIdleLongEnough(View view,
                                            CommunityPolicy policy,
                                            Map<String, Long> emptySince,
                                            long nowMs) {
        return view.members() == 0 && option(emptySince.get(view.id())).filter(since -> nowMs - since >= policy.dissolveGraceSeconds() * 1000L)
                                            .isPresent();
    }

    /// One community of a source is dissolved at a time, the newest idle one, so two empty communities never take each other's
    /// place at once and leave the source with none.
    private static boolean isNewestIdle(View view,
                                        List<View> all,
                                        CommunityPolicy policy,
                                        Map<String, Long> emptySince,
                                        long nowMs) {
        return all.stream()
                  .filter(other -> other.source()
                                        .equals(view.source()) && other.isOpen())
                  .filter(other -> isIdleLongEnough(other, policy, emptySince, nowMs))
                  .allMatch(other -> newer(view, other));
    }

    private static boolean newer(View view, View other) {
        return other.id()
                    .equals(view.id()) || index(view).or(-1) > index(other).or(-1);
    }

    private static Option<Integer> index(View view) {
        return CommunityGrowthPolicy.derivedIndex(view.source(), view.id());
    }

    /// Another open community of the same source stays. The last community of a source is never dissolved by policy.
    private static boolean hasSibling(View view, List<View> all) {
        return all.stream()
                  .anyMatch(other -> !other.id()
                                           .equals(view.id())
                                     && other.source()
                                             .equals(view.source())
                                     && other.isOpen());
    }

    record unused() implements DerivedCommunityPlan {}
}
