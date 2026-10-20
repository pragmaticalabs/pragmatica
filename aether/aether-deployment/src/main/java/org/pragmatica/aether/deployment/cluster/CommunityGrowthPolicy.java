// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.lang.Option;


/// The growth policy for DERIVED communities (#241): which community a joining worker takes, and when a new one is minted.
/// A derived community is `<source>-w-N`, minted by the cluster for a source that has no declared `[community.*]`.
///
/// Pure and total: the same candidates always give the same decision, ties broken by id, so two leaders that read the same
/// committed cut choose the same community. It never moves a member: it only answers for a worker that has no community yet
/// (assignment is immutable for the life of a node id; moving a worker is a make-before-break replacement). Growth therefore
/// adds capacity, a new community starts small, and it fills from joiners only.
///
/// The choice, in order:
/// 1. a FORMING or DEGRADED community with room, the least loaded first: an undersized community cannot take work, so it
///    is filled before anything is packed;
/// 2. an ACTIVE community with room, the fullest first (pack);
/// 3. a new community, while the source has fewer than `maxCommunities` that are not dissolved;
/// 4. at that bound, the least loaded community still below the hard ceiling;
/// 5. otherwise a refusal.
///
/// `load` is a live-aware count supplied by the caller, so a dead member that still holds a directive does not hold a slot.
/// The cap is soft by the number of joins that commit in one consensus round; the ceiling is checked against the same load.
public sealed interface CommunityGrowthPolicy {
    Pattern DIGITS = Pattern.compile("[0-9]{1,9}");

    /// One existing community of the source. Dissolved and dissolving communities are passed too: they are never chosen, but
    /// their numbers are never reused.
    record Candidate(String id, CommunityState state, int load) {
        public static Candidate candidate(String id, CommunityState state, int load) {
            return new Candidate(id, state, load);
        }

        boolean isOpen() {
            return state == CommunityState.FORMING || state == CommunityState.ACTIVE || state == CommunityState.DEGRADED;
        }

        boolean isUndersized() {
            return state == CommunityState.FORMING || state == CommunityState.DEGRADED;
        }

        boolean isLive() {
            return state != CommunityState.DISSOLVING && state != CommunityState.DISSOLVED;
        }
    }

    sealed interface Decision {
        /// The worker takes an existing community.
        record Join(String communityId) implements Decision {
            public static Join join(String communityId) {
                return new Join(communityId);
            }
        }

        /// The worker takes a community that does not exist yet; the assignment mints it.
        record Mint(String communityId) implements Decision {
            public static Mint mint(String communityId) {
                return new Mint(communityId);
            }
        }

        /// No community can take the worker: every one is at the ceiling and the source is at its community bound.
        enum Refusal implements Decision {
            BOUNDS_REACHED
        }
    }

    /// The decision for a joining worker of `source`, given every derived community the source has ever had.
    static Decision pick(String source, List<Candidate> communities, CommunityPolicy policy) {
        var open = communities.stream().filter(Candidate::isOpen).toList();

        return undersized(open, policy).orElse(() -> packed(open, policy))
                                       .orElse(() -> minted(source, communities, policy))
                                       .orElse(() -> leastLoaded(open))
                                       .or(Decision.Refusal.BOUNDS_REACHED);
    }

    private static Option<Decision> undersized(List<Candidate> open, CommunityPolicy policy) {
        return Option.from(open.stream()
                               .filter(Candidate::isUndersized)
                               .filter(candidate -> candidate.load() < policy.maxSize())
                               .min(byLoadThenId()))
                     .<Decision> map(candidate -> Decision.Join.join(candidate.id()));
    }

    private static Option<Decision> packed(List<Candidate> open, CommunityPolicy policy) {
        return Option.from(open.stream()
                               .filter(candidate -> !candidate.isUndersized())
                               .filter(candidate -> candidate.load() < policy.maxSize())
                               .min(byLoadThenId().reversed()
                                                  .thenComparing(Candidate::id)))
                     .<Decision> map(candidate -> Decision.Join.join(candidate.id()));
    }

    private static Option<Decision> minted(String source, List<Candidate> communities, CommunityPolicy policy) {
        return communities.stream()
                          .filter(Candidate::isLive)
                          .count() < policy.maxCommunities()
               ? Option.<Decision> some(Decision.Mint.mint(source + "-w-" + nextIndex(source, communities)))
               : Option.<Decision> none();
    }

    private static Option<Decision> leastLoaded(List<Candidate> open) {
        return Option.from(open.stream()
                               .filter(candidate -> candidate.load() < CommunityPolicy.CEILING)
                               .min(byLoadThenId()))
                     .<Decision> map(candidate -> Decision.Join.join(candidate.id()));
    }

    private static Comparator<Candidate> byLoadThenId() {
        return Comparator.comparingInt(Candidate::load)
                         .thenComparing(Candidate::id);
    }

    private static int nextIndex(String source, List<Candidate> communities) {
        return communities.stream()
                          .flatMap(candidate -> derivedIndex(source, candidate.id()).stream())
                          .mapToInt(Integer::intValue)
                          .max()
                          .orElse(-1) + 1;
    }

    /// The `N` of `<source>-w-N`, none when `communityId` is not a derived id of `source`.
    static Option<Integer> derivedIndex(String source, String communityId) {
        var prefix = source + "-w-";

        return Option.option(communityId)
                     .filter(id -> id.startsWith(prefix))
                     .map(id -> id.substring(prefix.length()))
                     .filter(tail -> DIGITS.matcher(tail).matches())
                     .map(Integer::parseInt);
    }

    record unused() implements CommunityGrowthPolicy {}
}
