// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy.Candidate;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Reads the derived communities of one source off the leader's committed view, with the live-aware load the growth policy
/// ([CommunityGrowthPolicy]) chooses by (#241).
///
/// A member's directive holds a slot only while the leader has not observed the member absent, so a dead worker whose directive
/// is kept until its termination is confirmed does not keep a community full. A joiner assigned a moment ago counts through the
/// leader's own [CommunityAdmissionLedger] until its directive is visible.
sealed interface DerivedCommunities {
    /// The directives of one snapshot of the KV: the live load per community, and every node that snapshot holds a directive for.
    /// The nodes are the "already visible" test for in-flight assignments, so a directive that lands after the snapshot is counted as in
    /// flight and never as nothing: reading it from the live store a moment later would drop it from both counts.
    record Directives(Map<String, Integer> loads, Set<NodeId> visible) {}

    static List<Candidate> candidates(ClusterDeploymentContext ctx, String source, long nowMs) {
        var directives = directives(ctx, nowMs);
        var pending = ctx.communityAdmission().pendingByCommunity(nowMs, directives.visible()::contains);
        var found = new ArrayList<Candidate>();

        collect(ctx, source, directives.loads(), pending, found);
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
        if (CommunityGrowthPolicy.derivedIndex(source, key.communityId()).isPresent()) {
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

    private static Directives directives(ClusterDeploymentContext ctx, long nowMs) {
        var directives = new Directives(new HashMap<>(), new HashSet<>());

        ctx.kvStore()
           .forEach(ActivationDirectiveKey.class,
                    ActivationDirectiveValue.class,
                    (key, directive) -> countIfLive(ctx, nowMs, directives, key, directive));

        return directives;
    }

    @Contract
    private static void countIfLive(ClusterDeploymentContext ctx,
                                    long nowMs,
                                    Directives directives,
                                    ActivationDirectiveKey key,
                                    ActivationDirectiveValue directive) {
        directives.visible().add(key.nodeId());
        if (ctx.communityAdmission().isRecent(key.nodeId(), nowMs) || !ctx.communityLiveness().isAbsent(key.nodeId())) {
            directives.loads().merge(directive.communityId(), 1, Integer::sum);
        }
    }

    record unused() implements DerivedCommunities {}
}
