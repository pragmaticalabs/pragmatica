// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy.Candidate;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.lang.Contract;


/// Reads the derived communities of one source off the leader's committed view, with the live-aware load the growth policy
/// ([CommunityGrowthPolicy]) chooses by (#241).
///
/// A member's directive holds a slot only while the leader has not observed the member absent, so a dead worker whose directive
/// is kept until its termination is confirmed does not keep a community full. A joiner assigned a moment ago counts through the
/// leader's own [CommunityAdmissionLedger] until its directive is visible.
sealed interface DerivedCommunities {
    static List<Candidate> candidates(ClusterDeploymentContext ctx, String source, long nowMs) {
        var loads = liveLoads(ctx);
        var pending = ctx.communityAdmission()
                         .pendingByCommunity(nowMs,
                                             node -> ctx.kvStore()
                                                        .get(ActivationDirectiveKey.activationDirectiveKey(node))
                                                        .isPresent());
        var found = new ArrayList<Candidate>();

        collect(ctx, source, loads, pending, found);
        addPendingMints(source, pending, found);

        return List.copyOf(found);
    }

    @Contract
    private static void collect(ClusterDeploymentContext ctx,
                                String source,
                                Map<String, Integer> loads,
                                Map<String, Integer> pending,
                                List<Candidate> found) {
        ctx.kvStore()
           .forEach(CommunityKey.class,
                    CommunityValue.class,
                    (key, value) -> addIfDerivedOf(source, loads, pending, found, key, value));
    }

    @Contract
    private static void addIfDerivedOf(String source,
                                       Map<String, Integer> loads,
                                       Map<String, Integer> pending,
                                       List<Candidate> found,
                                       CommunityKey key,
                                       CommunityValue value) {
        if (value.sourceName().equals(source) && CommunityGrowthPolicy.derivedIndex(source,
                                                                                    key.communityId())
                                                                      .isPresent()) {
            found.add(Candidate.candidate(key.communityId(),
                                          value.state(),
                                          loads.getOrDefault(key.communityId(), 0) + pending.getOrDefault(key.communityId(),
                                                                                                          0)));
        }
    }

    /// A community minted by an assignment still in flight is not in the KV yet. It is a FORMING candidate all the same, or the
    /// next joiner would decide to mint the same id and have its transaction refused against the first one's.
    @Contract
    private static void addPendingMints(String source, Map<String, Integer> pending, List<Candidate> found) {
        pending.forEach((communityId, count) -> addPendingMint(source, communityId, count, found));
    }

    @Contract
    private static void addPendingMint(String source, String communityId, int count, List<Candidate> found) {
        if (CommunityGrowthPolicy.derivedIndex(source, communityId).isPresent() && found.stream()
                                                                                        .noneMatch(candidate -> candidate.id()
                                                                                                                         .equals(communityId))) {
            found.add(Candidate.candidate(communityId, CommunityState.FORMING, count));
        }
    }

    private static Map<String, Integer> liveLoads(ClusterDeploymentContext ctx) {
        var loads = new HashMap<String, Integer>();

        ctx.kvStore()
           .forEach(ActivationDirectiveKey.class,
                    ActivationDirectiveValue.class,
                    (key, directive) -> countIfLive(ctx, loads, key, directive));

        return loads;
    }

    @Contract
    private static void countIfLive(ClusterDeploymentContext ctx,
                                    Map<String, Integer> loads,
                                    ActivationDirectiveKey key,
                                    ActivationDirectiveValue directive) {
        if (!directive.communityId().isBlank() && !ctx.communityLiveness().isAbsent(key.nodeId())) {
            loads.merge(directive.communityId(), 1, Integer::sum);
        }
    }

    record unused() implements DerivedCommunities {}
}
