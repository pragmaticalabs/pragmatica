// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityPolicyView;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// R-CF.17 (#241), the parts the parity census did not check: the admission ledger's dedup under concurrent joins (NT-151) and the growth
/// warnings' parity with LEGACY (NT-150). The legacy ledger serialized read-decide-register under a lock; FSM2 decides one input at a time
/// over one model and counts its in-flight DIRECTIVE drafts, so joins delivered in ONE drain (`deliverTogether`) are the concurrent case.
class CommunityGrowthLedgerTest {
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final NodeId W4 = node("worker-4");
    private static final NodeId W5 = node("worker-5");
    private static final String CLUSTER = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        zones = ["a"]
        [source.east.core]
        count = 3
        [source.east.worker]
        count = 20
        """;
    private static final String GROWTH_MAX_3 = "[community_policy]\nmax_size = 3\ngrowth = true\n";
    private static final String GROWTH_ONE_COMMUNITY = "[community_policy]\nmax_size = 3\ngrowth = true\nmax_communities = 1\n";
    private static final String GROWTH_TWO_COMMUNITIES = "[community_policy]\nmax_size = 3\ngrowth = true\nmax_communities = 2\n";

    private static ClusterConfigValue config(String policy, long version) {
        return new ClusterConfigValue(Option.some(CLUSTER + policy), "test", "1.0.0", List.of(), 3, 3, "forge", version, 0L);
    }

    private static MbWorld eastUnder(String policy) {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of())
                                            .withMemberSource(Map.of(W1, "east", W2, "east", W3, "east", W4, "east", W5, "east")));

        w.h.seedQuietly(ClusterConfigKey.CURRENT, config(policy, 1L));

        return w;
    }

    /// `east-w-0` already at the hard ceiling (200 members that hold a slot) under `max_communities = 1`: no community can take a joiner.
    private static MbWorld eastAtTheBound() {
        var w = eastUnder(GROWTH_ONE_COMMUNITY);

        w.h.seedQuietly(new CommunityKey("east-w-0"), community("east", 3, CommunityState.ACTIVE));
        IntStream.range(0, CommunityPolicy.CEILING)
                 .forEach(i -> w.h.seedQuietly(new ActivationDirectiveKey(node("member-" + i)), worker("east-w-0")));

        return w.activate();
    }

    private static ClusterInput.WorkerJoin join(MbWorld w, NodeId node, long token) {
        return new ClusterInput.WorkerJoin(node, w.admit(node, token));
    }

    private static String communityOf(MbWorld w, NodeId node) {
        return w.stored(new ActivationDirectiveKey(node))
                .filter(ActivationDirectiveValue.class::isInstance)
                .map(value -> ((ActivationDirectiveValue) value).communityId())
                .or("");
    }

    private static long directiveWritesFor(MbWorld w, NodeId node) {
        return w.h.mutations(ActivationDirectiveKey.class)
                  .stream()
                  .filter(mutation -> mutation.key()
                                              .equals(new ActivationDirectiveKey(node)))
                  .count();
    }

    private static long mintsOf(MbWorld w, String communityId) {
        return w.h.mutations(CommunityKey.class)
                  .stream()
                  .filter(mutation -> mutation.key()
                                              .equals(new CommunityKey(communityId)) && mutation.expected()
                                                                                                .isEmpty())
                  .count();
    }

    /// NT-151: four workers joining in ONE drain under `max_size = 3`: the first three take `east-w-0` (one mint, the others join the
    /// minting draft), the fourth sees three in-flight assignments and mints `east-w-1`. Without the in-flight count all four pack into
    /// `east-w-0`.
    @Test
    void concurrentJoins_inOneDrain_neverOverfillACommunity() {
        var w = eastUnder(GROWTH_MAX_3).activate();

        w.h.deliverTogether(join(w, W1, 21L), join(w, W2, 22L), join(w, W3, 23L), join(w, W4, 24L));

        assertThat(List.of(communityOf(w, W1), communityOf(w, W2), communityOf(w, W3), communityOf(w, W4))).containsExactly("east-w-0",
                                                                                                                        "east-w-0",
                                                                                                                        "east-w-0",
                                                                                                                        "east-w-1");
        assertThat(List.of(mintsOf(w, "east-w-0"), mintsOf(w, "east-w-1"))).as("each community minted exactly once").containsExactly(1L, 1L);
    }

    /// NT-151, the duplicate: the same worker's join delivered twice in one drain decides once: one directive write, and the duplicate
    /// holds no second slot (the next joiner still fits `east-w-0`).
    @Test
    void duplicateJoins_ofOneWorker_inOneDrain_decideOnce() {
        var w = eastUnder(GROWTH_MAX_3).activate();
        var first = join(w, W1, 21L);

        w.h.deliverTogether(first, first, join(w, W2, 22L), join(w, W3, 23L));

        assertThat(directiveWritesFor(w, W1)).isEqualTo(1L);
        assertThat(List.of(communityOf(w, W1), communityOf(w, W2), communityOf(w, W3))).containsOnly("east-w-0");
    }

    /// The in-flight count ends when the directive is visible: a later join counts the committed member once, not twice.
    @Test
    void anInFlightAssignment_stopsCounting_onceItsDirectiveIsVisible() {
        var w = eastUnder(GROWTH_MAX_3).activate();

        w.h.deliver(join(w, W1, 21L));
        w.h.deliver(join(w, W2, 22L));
        w.h.deliver(join(w, W3, 23L));

        assertThat(Directory.inFlightByCommunity(w.h.model())).as("every directive committed: nothing in flight").isEmpty();
        assertThat(List.of(communityOf(w, W1), communityOf(w, W2), communityOf(w, W3))).containsOnly("east-w-0");
    }

    /// NT-150: the growth refusal is reported as LEGACY reports it (`refuseGrowth`): the same catalogue code, the source as the subject.
    /// The legacy policy over the same candidates refuses, so the test is not vacuous.
    @Test
    void growthBlocked_isRaisedForTheSource_likeLegacy() {
        var w = eastAtTheBound();
        var legacy = CommunityPolicyView.communityPolicyView(w.h.store, CommunitySizing.communitySizing(100, 3))
                                        .current();

        assertThat(CommunityGrowthPolicy.pick("east",
                                              List.of(CommunityGrowthPolicy.Candidate.candidate("east-w-0",
                                                                                                CommunityState.ACTIVE,
                                                                                                CommunityPolicy.CEILING)),
                                              legacy)).isEqualTo(CommunityGrowthPolicy.Decision.Refusal.BOUNDS_REACHED);

        w.h.deliver(join(w, W1, 21L));

        assertThat(w.h.warns(OperatorWarningCode.COMMUNITY_GROWTH_BLOCKED.code())).singleElement()
                                                                                 .satisfies(warn -> assertThat(warn.subject()).isEqualTo("east"));
        assertThat(communityOf(w, W1)).as("a refused worker gets no community").isEmpty();
        assertThat(w.h.warns(OperatorWarningCode.COMMUNITY_GROWTH_RESUMED.code())).isEmpty();
    }

    /// NT-150: the next admission for a blocked source raises the recovery, same subject, once.
    @Test
    void growthResumed_closesTheBlock_onTheNextAdmission() {
        var w = eastAtTheBound();

        w.h.deliver(join(w, W1, 21L));
        w.h.seed(ClusterConfigKey.CURRENT, config(GROWTH_TWO_COMMUNITIES, 2L));
        w.h.deliver(join(w, W2, 22L));
        w.h.deliver(join(w, W3, 23L));

        assertThat(communityOf(w, W2)).isEqualTo("east-w-1");
        assertThat(w.h.warns(OperatorWarningCode.COMMUNITY_GROWTH_RESUMED.code())).singleElement()
                                                                                 .satisfies(warn -> assertThat(warn.subject()).isEqualTo("east"));
    }

    /// An admission with no block before it raises no recovery.
    @Test
    void growthResumed_isNotRaised_withoutABlock() {
        var w = eastUnder(GROWTH_MAX_3).activate();

        w.h.deliver(join(w, W1, 21L));

        assertThat(w.h.warns(OperatorWarningCode.COMMUNITY_GROWTH_RESUMED.code())).isEmpty();
    }

    /// Growth off: the derived community is `<source>-w-0` at the committed max_size, never a second one (R-CF.16).
    @Test
    void growthOff_packsEveryWorkerIntoTheSourcesSingleCommunity() {
        var w = eastUnder("[community_policy]\nmax_size = 3\n").activate();

        w.h.deliverTogether(join(w, W1, 21L), join(w, W2, 22L), join(w, W3, 23L), join(w, W4, 24L));

        assertThat(communityOf(w, W4)).isEqualTo("east-w-0");
        assertThat(w.stored(new CommunityKey("east-w-0")).map(value -> ((AetherValue.CommunityValue) value).targetSize())).isEqualTo(Option.some(3));
    }
}
