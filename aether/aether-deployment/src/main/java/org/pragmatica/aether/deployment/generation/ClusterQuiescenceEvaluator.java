// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import java.util.ArrayList;
import java.util.Collection;

import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;


/// Pure cluster- and community-quiescence verdict logic. Reads the minimal inputs the verdict
/// actually depends on (member health-hints and per-community quiescence states) so it can be
/// reused live by HTTP routes without the surrounding projector machinery. No KV/IO/state.
public sealed interface ClusterQuiescenceEvaluator {
    record ClusterResult(ClusterQuiescence quiescence, String detail) {}

    record CommunityResult(CommunityQuiescence state, String detail) {}

    /// Cluster-level verdict from member health-hints, per-community quiescence states and the
    /// pending spokesman-rebalance count. FAULTY/SUSPECTED members or DEGRADED/DISSOLVING
    /// communities → DEGRADED; CONVERGING communities or pending rebalance → CONVERGING; else
    /// QUIESCED.
    static ClusterResult evaluateCluster(Collection<HealthHint> memberHealths,
                                         Collection<CommunityQuiescence> communityStates,
                                         int pendingRebalanceCount) {
        var memberSnapshot = summarizeMembers(memberHealths);
        var communitySnapshot = summarizeCommunities(communityStates);

        if (memberSnapshot.hasFaulty() || memberSnapshot.hasSuspected() || communitySnapshot.hasDegraded()) {
            return new ClusterResult(ClusterQuiescence.DEGRADED, buildDegradedDetail(memberSnapshot, communitySnapshot));
        }

        if (communitySnapshot.hasConverging() || pendingRebalanceCount > 0) {
            return new ClusterResult(ClusterQuiescence.CONVERGING,
                                     buildConvergingDetail(communitySnapshot, pendingRebalanceCount));
        }

        return new ClusterResult(ClusterQuiescence.QUIESCED, "");
    }

    /// Community-level verdict. Suspected/faulty counts are always 0 in production, so this hard-wires
    /// them: dissolved → DISSOLVING; governor not acked the community epoch → CONVERGING; else QUIESCED.
    static CommunityResult evaluateCommunity(GovernorAnnouncementValue announcement, Epoch lastAckAtCore) {
        if (announcement.dissolved()) {
            return new CommunityResult(CommunityQuiescence.DISSOLVING, "community dissolved");
        }

        if (lastAckAtCore.compareTo(announcement.communityEpoch()) < 0) {
            return new CommunityResult(CommunityQuiescence.CONVERGING,
                                       "governor has not acked epoch " + announcement.communityEpoch());
        }

        return new CommunityResult(CommunityQuiescence.QUIESCED, "");
    }

    private static MemberSnapshot summarizeMembers(Collection<HealthHint> memberHealths) {
        var faulty = 0;
        var suspected = 0;

        for (var hint : memberHealths) {
            if (hint == HealthHint.FAULTY) {
                faulty++;
            }

            if (hint == HealthHint.SUSPECTED) {
                suspected++;
            }
        }

        return new MemberSnapshot(faulty, suspected);
    }

    private static CommunityStatusSnapshot summarizeCommunities(Collection<CommunityQuiescence> communityStates) {
        var degraded = 0;
        var converging = 0;
        var dissolving = 0;

        for (var state : communityStates) {
            switch (state) {
                case DEGRADED -> degraded++;
                case CONVERGING -> converging++;
                case DISSOLVING -> dissolving++;
                case QUIESCED -> {}
            }
        }

        return new CommunityStatusSnapshot(degraded, converging, dissolving);
    }

    private static String buildDegradedDetail(MemberSnapshot members, CommunityStatusSnapshot communities) {
        var parts = new ArrayList<String>();

        if (members.faulty() > 0) {
            parts.add(members.faulty() + " members FAULTY");
        }

        if (members.suspected() > 0) {
            parts.add(members.suspected() + " members SUSPECTED");
        }

        if (communities.degraded() > 0) {
            parts.add(communities.degraded() + " communities DEGRADED");
        }

        if (communities.dissolving() > 0) {
            parts.add(communities.dissolving() + " communities DISSOLVING");
        }

        return String.join("; ", parts);
    }

    private static String buildConvergingDetail(CommunityStatusSnapshot communities, int pendingRebalance) {
        var parts = new ArrayList<String>();

        if (communities.converging() > 0) {
            parts.add(communities.converging() + " communities CONVERGING");
        }

        if (pendingRebalance > 0) {
            parts.add(pendingRebalance + " communities awaiting spokesman");
        }

        return String.join("; ", parts);
    }

    record MemberSnapshot(int faulty, int suspected) implements ClusterQuiescenceEvaluator {
        boolean hasFaulty() {
            return faulty > 0;
        }

        boolean hasSuspected() {
            return suspected > 0;
        }
    }

    record CommunityStatusSnapshot(int degraded, int converging, int dissolving) implements ClusterQuiescenceEvaluator {
        boolean hasDegraded() {
            return degraded > 0;
        }

        boolean hasConverging() {
            return converging > 0 || dissolving > 0;
        }
    }
}
