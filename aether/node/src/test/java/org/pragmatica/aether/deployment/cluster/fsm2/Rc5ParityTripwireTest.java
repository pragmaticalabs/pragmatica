// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityPolicyView;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicPublisherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicPublisherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicTier;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.harness;

/// rc5 behaviour of the LEGACY deployment engine that FSM2 does not have yet (fsm2-parity-census, CUTOVER-BLOCKER rows). Each gap is pinned
/// twice: an ENABLED tripwire asserting what FSM2 does today, with a non-vacuity check that the legacy side really differs, and a `@Disabled`
/// parity test beside it. When FSM2 gains the behaviour the tripwire goes red: enable the parity test and delete the tripwire. Every parity
/// test is required before the FSM2 cutover.
class Rc5ParityTripwireTest {
    private static final String PARITY = "required before the FSM2 cutover";
    private static final long TOKEN_2 = 0x2222L;
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final NodeId W4 = node("worker-4");
    private static final int SETTINGS_TARGET = 100;
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

    // -- #1337, cluster side: a departed node's topic rows -----------------------------------------------------------------
    // Legacy (rc5 6fc194e39): the node-removal batch removes the departed node's TopicSubscription and TopicPublisher rows
    // (`ClusterDeploymentState.topicRowCommandsForNode`), and the periodic sweep removes any left behind
    // (`StaleEntryCleaner.cleanupStaleTopicRows`). FSM2's C17/C17b remove the node's desired, observed and route rows only.
    private static final ResourceAddress ORDERS_TOPIC = ResourceAddress.resourceAddress("org.example", "orders", "1.0.0").unwrap();
    private static final TopicSubscriptionKey SUBSCRIPTION = TopicSubscriptionKey.topicSubscriptionKey(ORDERS_TOPIC,
                                                                                                       ORDERS,
                                                                                                       MethodName.methodName("onOrder").unwrap(),
                                                                                                       CORE_2);
    private static final TopicPublisherKey PUBLISHER = TopicPublisherKey.topicPublisherKey(ORDERS_TOPIC, ORDERS, CORE_2);

    /// core-2 runs ORDERS (goal and row), has routes for it, and wrote a subscription row and a publisher row for its topic.
    private static MbWorld departingNodeWithTopicRows() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .routes(CORE_2, ORDERS);
        w.h.seedQuietly(SUBSCRIPTION, TopicSubscriptionValue.topicSubscriptionValue(CORE_2, TopicTier.ANY));
        w.h.seedQuietly(PUBLISHER, TopicPublisherValue.topicPublisherValue(CORE_2, TopicTier.EPHEMERAL));

        return w.activate();
    }

    private static MbWorld departed() {
        var w = departingNodeWithTopicRows();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.drain();

        return w;
    }

    /// CUTOVER-BLOCKER tripwire (#1337): FSM2's departure cleanup ran (the node's observed row is gone) and left its topic rows.
    @Test
    void tripwire_aDepartedNodesTopicRows_surviveTheFsm2DepartureCleanup() {
        var w = departed();

        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("the departure cleanup ran, so the tripwire is not vacuous").isFalse();
        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("#1337 parity: " + PARITY
                                                                              + ": FSM2 now removes a departed node's topic rows; enable parity_aDepartedNodesTopicRows_areRemoved_likeLegacy and delete this tripwire")
                                                                          .containsExactly(true, true);
    }

    @Disabled("#1337 parity: " + PARITY)
    @Test
    void parity_aDepartedNodesTopicRows_areRemoved_likeLegacy() {
        var w = departed();

        assertThat(List.of(w.present(SUBSCRIPTION), w.present(PUBLISHER))).as("#1337 parity: " + PARITY
                                                                              + ": a departed node's topic rows are removed, as the legacy node-removal batch and stale sweep do")
                                                                          .containsExactly(false, false);
    }

    // -- #241: the committed community policy in derived-community assignment ---------------------------------------------
    // Legacy (rc5 2bb886e86, 31d5989db): `ClusterDeploymentState.assignDerivedCommunity` reads the COMMITTED `[community_policy]`
    // (`CommunityPolicyView`): `max_size` is the derived community's target size, and `growth = true` routes the join through
    // `CommunityGrowthPolicy` (join, mint `<source>-w-N`, refuse) under the `CommunityAdmissionLedger`. FSM2's J5 (`Directory.assignWorker`)
    // commits `<source>-w-0` with the node-level `Settings.communityTargetSize()`.
    private static MbWorld workersOfEastUnder(String policy) {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of())
                                            .withMemberSource(Map.of(W1, "east", W2, "east", W3, "east", W4, "east")));

        w.h.seedQuietly(ClusterConfigKey.CURRENT,
                        new ClusterConfigValue(Option.some(CLUSTER + policy), "test", "1.0.0", List.of(), 3, 3, "forge", 1L, 0L));

        return w.activate();
    }

    private static CommunityPolicyView legacyView(MbWorld w) {
        return CommunityPolicyView.communityPolicyView(w.h.store, CommunitySizing.communitySizing(SETTINGS_TARGET, 3));
    }

    private static void join(MbWorld w, NodeId node, long token) {
        w.h.deliver(new ClusterInput.WorkerJoin(node, w.admit(node, token)));
    }

    private static String communityOf(MbWorld w, NodeId node) {
        return w.stored(new ActivationDirectiveKey(node))
                .filter(ActivationDirectiveValue.class::isInstance)
                .map(value -> ((ActivationDirectiveValue) value).communityId())
                .or("");
    }

    private static Option<CommunityValue> communityValue(MbWorld w, String id) {
        return w.stored(new CommunityKey(id))
                .filter(CommunityValue.class::isInstance)
                .map(CommunityValue.class::cast);
    }

    /// CUTOVER-BLOCKER tripwire (#241): with `max_size = 7` committed, FSM2 still mints `east-w-0` at the node-level target size.
    @Test
    void tripwire_aDerivedCommunity_takesTheNodeSizing_notTheCommittedMaxSize() {
        var w = workersOfEastUnder("[community_policy]\nmax_size = 7\n");

        join(w, W1, 21L);
        assertThat(legacyView(w).current().maxSize()).as("the legacy reader sees the committed max_size, so the tripwire is not vacuous").isEqualTo(7);
        assertThat(communityOf(w, W1)).isEqualTo("east-w-0");
        assertThat(communityValue(w, "east-w-0").map(CommunityValue::targetSize)).as("#241 parity: " + PARITY
                                                                                + ": FSM2 now stamps the committed max_size; enable parity_aDerivedCommunity_takesTheCommittedMaxSize_likeLegacy and delete this tripwire")
                                                                            .isEqualTo(Option.some(SETTINGS_TARGET));
    }

    @Disabled("#241 parity: " + PARITY)
    @Test
    void parity_aDerivedCommunity_takesTheCommittedMaxSize_likeLegacy() {
        var w = workersOfEastUnder("[community_policy]\nmax_size = 7\n");

        join(w, W1, 21L);
        assertThat(communityValue(w, "east-w-0").map(CommunityValue::targetSize)).as("#241 parity: " + PARITY
                                                                                + ": the derived community's target is the committed max_size")
                                                                            .isEqualTo(Option.some(legacyView(w).current().maxSize()));
    }

    /// The legacy growth decision for the fourth worker, over the community the first three filled.
    private static CommunityGrowthPolicy.Decision legacyDecisionForTheFourth(MbWorld w) {
        var filled = communityValue(w, "east-w-0").unwrap();

        return CommunityGrowthPolicy.pick("east",
                                          List.of(CommunityGrowthPolicy.Candidate.candidate("east-w-0", filled.state(), 3)),
                                          legacyView(w).current());
    }

    private static MbWorld fourWorkersUnderGrowth() {
        var w = workersOfEastUnder("[community_policy]\nmax_size = 3\ngrowth = true\n");

        join(w, W1, 21L);
        join(w, W2, 22L);
        join(w, W3, 23L);
        join(w, W4, 24L);

        return w;
    }

    /// CUTOVER-BLOCKER tripwire (#241): with growth on and `east-w-0` full at `max_size = 3`, the legacy growth policy mints a new community for
    /// the fourth worker; FSM2 puts it in `east-w-0`.
    @Test
    void tripwire_growth_fsm2PacksTheFourthWorkerIntoTheFullCommunity() {
        var w = fourWorkersUnderGrowth();

        assertThat(legacyDecisionForTheFourth(w)).as("the legacy growth policy mints, so the tripwire is not vacuous")
                                                 .isEqualTo(CommunityGrowthPolicy.Decision.Mint.mint("east-w-1"));
        assertThat(communityOf(w, W4)).as("#241 parity: " + PARITY
                                          + ": FSM2 now applies the growth policy; enable parity_growth_theFourthWorkerTakesTheGrowthDecision_likeLegacy and delete this tripwire")
                                      .isEqualTo("east-w-0");
    }

    @Disabled("#241 parity: " + PARITY)
    @Test
    void parity_growth_theFourthWorkerTakesTheGrowthDecision_likeLegacy() {
        var w = fourWorkersUnderGrowth();

        assertThat(communityOf(w, W4)).as("#241 parity: " + PARITY + ": the fourth worker takes the community the growth policy decides")
                                      .isEqualTo("east-w-1");
    }

    // -- #241: the committed viability floor in community-state evaluation -------------------------------------------------
    // Legacy (rc5 2bb886e86): `ClusterDeploymentState` judges FORMING/ACTIVE/DEGRADED against the COMMITTED policy's
    // `viability_floor` (`ctx.communityPolicy().current().viabilityFloor()`). FSM2's reconcile step 5 (`Communities.evaluate`) uses the
    // node-level `Settings.communityViabilityFloor()`.
    private static FsmHarness formingCommunityOfThreeUnderFloorFour() {
        var h = harness(CORE_1);

        h.seams = h.seams.withLocalAlive(Option.some(Set.of(W1, W2, W3)));
        h.seedQuietly(ClusterConfigKey.CURRENT,
                      new ClusterConfigValue(Option.some(CLUSTER + "[community_policy]\nviability_floor = 4\n"),
                                             "test",
                                             "1.0.0",
                                             List.of(),
                                             3,
                                             3,
                                             "forge",
                                             1L,
                                             0L));
        h.seedQuietly(new CommunityKey("c"), community("default", 3, CommunityState.FORMING));
        for (var w : List.of(W1, W2, W3)) {
            h.seedQuietly(new ActivationDirectiveKey(w), worker("c"));
        }
        h.seedQuietly(new GovernorAnnouncementKey("c"), roster(W1, false, W1, W2, W3));
        h.activate();
        h.advanceBySeconds(1);
        h.advance(31_000L);

        return h;
    }

    private static Option<CommunityState> stateOfC(FsmHarness h) {
        return h.store.get(new CommunityKey("c"))
                      .filter(CommunityValue.class::isInstance)
                      .map(value -> ((CommunityValue) value).state());
    }

    /// CUTOVER-BLOCKER tripwire (#241): with `viability_floor = 4` committed, FSM2 still activates a community of three live members.
    @Test
    void tripwire_communityState_isJudgedAgainstTheNodeFloor_notTheCommittedOne() {
        var h = formingCommunityOfThreeUnderFloorFour();
        var legacyFloor = CommunityPolicyView.communityPolicyView(h.store, CommunitySizing.communitySizing(SETTINGS_TARGET, 3))
                                             .current()
                                             .viabilityFloor();

        assertThat(legacyFloor).as("the legacy reader sees the committed floor, so the tripwire is not vacuous").isEqualTo(4);
        assertThat(stateOfC(h)).as("#241 parity: " + PARITY
                                   + ": FSM2 now judges against the committed floor; enable parity_communityState_isJudgedAgainstTheCommittedFloor_likeLegacy and delete this tripwire")
                               .isEqualTo(Option.some(CommunityState.ACTIVE));
    }

    @Disabled("#241 parity: " + PARITY)
    @Test
    void parity_communityState_isJudgedAgainstTheCommittedFloor_likeLegacy() {
        var h = formingCommunityOfThreeUnderFloorFour();

        assertThat(stateOfC(h)).as("#241 parity: " + PARITY + ": three live members are below the committed floor of four")
                               .isEqualTo(Option.some(CommunityState.FORMING));
    }
}
