// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityPlacementOperationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodePlacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.slice.kvstore.AetherValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The community a joining worker is assigned to (B-069, B-070, J5): the least-filled eligible policy, ties by id; the policy a
/// live placement operation reserved for the node; the zone its placement observed; the default community when nothing is
/// configured; and no directive into a dissolving or dissolved community.
class GapCommunityTest {
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");

    private static CommunityPlacement policy(String id, int target, String source, Option<String> zone) {
        return new CommunityPlacement(id, target, List.of(new Location(source, zone, 0, 1)));
    }

    private static MbWorld world(Map<String, CommunityPlacement> policies, Map<NodeId, String> sources) {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withCommunityPolicies(policies)
                                           .withMemberSource(sources))
                      .activate();
    }

    private static String communityOf(MbWorld w, NodeId node) {
        return w.h.store.get(new ActivationDirectiveKey(node))
                        .filter(ActivationDirectiveValue.class::isInstance)
                        .map(value -> ((ActivationDirectiveValue) value).communityId())
                        .or("");
    }

    private static void join(MbWorld w, NodeId node, long token) {
        w.h.deliver(new ClusterInput.WorkerJoin(node, w.admit(node, 1L, token)));
    }

    /// Red when: the choice is not the lowest fill ratio (assigned / targetSize), or ties are not broken by policy id, or a
    /// departed id still counts toward a community's fill (N4-4).
    @Test
    void worker_isAssignedToTheLeastFilledPolicy_tiesById() {
        var w = world(Map.of("p-a", policy("p-a", 4, "s1", Option.none()), "p-b", policy("p-b", 2, "s1", Option.none())),
                      Map.of(W1, "s1", W2, "s1", W3, "s1"));

        join(w, W1, 21L);
        join(w, W2, 22L);
        join(w, W3, 23L);
        assertThat(communityOf(w, W1)).as("both empty: ties by id").isEqualTo("p-a");
        assertThat(communityOf(w, W2)).as("p-a is 1/4 full, p-b is empty").isEqualTo("p-b");
        assertThat(communityOf(w, W3)).as("p-a 1/4, p-b 1/2").isEqualTo("p-a");
    }

    /// Red when: a live placement operation targeting this node and source does not decide the community (the emptier policy
    /// wins instead), or a COMPLETE operation still does.
    @Test
    void reservedPlacement_decidesTheCommunity_whileTheOperationIsActive() {
        var policies = Map.of("p-a", policy("p-a", 4, "s1", Option.none()), "p-b", policy("p-b", 4, "s1", Option.none()));
        var active = world(policies, Map.of(W1, "s1"));

        active.h.seed(new CommunityPlacementOperationKey("p-b"),
                      operation("p-b", W1, "s1", PlacementOperationPhase.AWAITING_READY));
        join(active, W1, 21L);
        assertThat(communityOf(active, W1)).isEqualTo("p-b");
        var complete = world(policies, Map.of(W1, "s1"));

        complete.h.seed(new CommunityPlacementOperationKey("p-b"),
                        operation("p-b", W1, "s1", PlacementOperationPhase.COMPLETE));
        join(complete, W1, 21L);
        assertThat(communityOf(complete, W1)).as("a finished operation reserves nothing: ties by id").isEqualTo("p-a");
    }

    private static CommunityPlacementOperationValue operation(String community,
                                                              NodeId target,
                                                              String source,
                                                              PlacementOperationPhase phase) {
        return new CommunityPlacementOperationValue("op-1",
                                                    community,
                                                    target,
                                                    source,
                                                    Option.none(),
                                                    "binding",
                                                    Option.none(),
                                                    "",
                                                    phase,
                                                    new LeaderValue(FsmHarness.SELF, 1L),
                                                    1L,
                                                    1L,
                                                    "");
    }

    /// Red when: a policy whose location names another zone than the node's observed zone is eligible.
    @Test
    void zone_restrictsTheEligiblePolicies() {
        var w = world(Map.of("p-a",
                             policy("p-a", 4, "s1", Option.some("z1")),
                             "p-b",
                             policy("p-b", 4, "s1", Option.some("z2"))),
                      Map.of(W1, "s1"));

        w.h.seed(new NodePlacementKey(W1), new NodePlacementValue("s1", Option.some("z2"), "i-1"));
        join(w, W1, 21L);
        assertThat(communityOf(w, W1)).isEqualTo("p-b");
    }

    /// Red when: with no policy configured the community is not `<source>-w-0` minted FORMING with the configured target size,
    /// or a second worker of the source mints it again instead of joining it.
    @Test
    void noPolicies_mintsTheSourceCommunityOnce() {
        var w = world(Map.of(), Map.of(W1, "east", W2, "east"));

        join(w, W1, 21L);
        join(w, W2, 22L);
        assertThat(communityOf(w, W1)).isEqualTo("east-w-0");
        assertThat(communityOf(w, W2)).isEqualTo("east-w-0");
        var minted = w.h.mutations(CommunityKey.class);

        assertThat(minted).as("minted once").hasSize(1);
        var community = (CommunityValue) minted.getFirst().replacement().or((AetherValue) null);

        assertThat(community.state()).isEqualTo(CommunityState.FORMING);
        assertThat(community.targetSize()).isEqualTo(100);
    }

    /// Red when: a blank or absent source label is not normalised to `default`.
    @Test
    void absentSource_isTheDefaultSource() {
        var w = world(Map.of(), Map.of());

        join(w, W1, 21L);
        assertThat(communityOf(w, W1)).isEqualTo("default-w-0");
    }

    /// Red when: a worker is given a directive into a community that is dissolving or dissolved.
    @Test
    void dissolvedCommunity_takesNoWorker() {
        var w = world(Map.of(), Map.of(W1, "east"));

        w.h.seed(new CommunityKey("east-w-0"), Fixtures.community("east", 3, CommunityState.DISSOLVED));
        join(w, W1, 21L);
        assertThat(communityOf(w, W1)).isEmpty();
        assertThat(w.h.submits(ClusterModel.SubmitKind.DIRECTIVE)).isEmpty();
    }

    /// Red when: a worker that already has a community directive is assigned again.
    @Test
    void workerWithADirective_isLeftAlone() {
        var w = world(Map.of(), Map.of(W1, "east"));

        w.h.seed(new ActivationDirectiveKey(W1), worker("elsewhere"));
        w.h.drain();
        join(w, W1, 21L);
        assertThat(communityOf(w, W1)).isEqualTo("elsewhere");
        assertThat(w.h.submits(ClusterModel.SubmitKind.DIRECTIVE)).isEmpty();
    }
}
