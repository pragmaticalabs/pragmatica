// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.Map;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The REQUIRED #367 pre-flight smoke (CTO ruling 2026-08-29, the pole-gate redefinition):
/// multi-source worker topology is the SHIPPED mechanism the GA ladder's 3×3 topology rides —
/// communities are minted one-per-source (`ClusterDeploymentState`, `source + "-w-0"`), so three
/// worker sources ARE three communities — but no test had ever exercised more than one worker
/// source. Shipped-but-unexercised is where surprises live; this smoke exercises exactly the
/// multi-source axis, in-JVM, before any credentialed round.
///
/// ## What is pinned
/// Two worker sources (`src-a`, `src-b`), two workers each, on a 5-core cluster (the
/// `CommunityFormationProbeTest` stability floor — a 3-core quorum cannot survive worker-add SWIM
/// churn). Workers self-assert BOTH labels exactly as production nodes do (`AETHER_ROLE` /
/// `AETHER_SOURCE` → SWIM labels): `resolveSource` reads the source label, so each join must mint
/// or join ITS source's community. Pinned: both `src-a-w-0` and `src-b-w-0` exist with the correct
/// `source` field; NO `default-w-0` exists (the probe proves bare unlabeled joins mint exactly
/// that, so its absence here is the proof the label round-trips rather than falling back); the
/// core count stays at the cap (no worker was promoted); every worker reaches FSM Member.
///
/// ## Deliberately OUT of smoke scope, with the reasoning recorded
/// Community ACTIVE state: FORMING → ACTIVE requires liveMembers ≥ 3 (RF floor), so two ACTIVE
/// communities need 11 in-JVM nodes — past the 8-node SWIM probe-ack starvation line that made
/// the single-source probe `@Skip`ped as an in-JVM gate (#336). The activation threshold is the
/// single-source-proven mechanism applied per community; what was UNPROVEN about multi-source —
/// distinct minting, correct assignment, no cross-source interference — is exactly what this
/// smoke pins. The first Hetzner rung observes ACTIVE per community on real hardware.
/// ## This test found #728, and is the gate that closes it
///
/// It ran RED twice on 2026-08-29 (isolated, under the machine suite-lock, non-default ports and
/// prefix). Measured signature, both runs: four workers joined; `DHT: Node added msrc-6/7/8/9` =
/// 0/0/0/0; `Received membership decision` = 0; mint + assign + promote lines = 0. Contamination
/// ruled out — every NodeId in both logs was `msrc-*`.
///
/// The cause was never this test. `MembershipDeltaProjector.processJoined` returned early for any
/// non-core role ("Wave 2 — a worker join never perturbs the core delta"), and its `emitJoin` is
/// the sole production emitter of `MembershipDecision.NodeJoined` — the only event reaching
/// `ClusterDeploymentState.assignNodeRole`, the only writer of community keys and worker
/// activation directives. So a node labelled `role=worker`, which is what every CTM-provisioned
/// worker is, never minted a community and never activated.
///
/// A positive control isolated it to that single label: a byte-identical run advertising the
/// source label ONLY minted both communities with correct source names, proving the mint path was
/// alive and the source label round-tripped.
///
/// #728 fixed it by routing non-core joins onto a separate [`WorkerJoinDecision`] channel that
/// `assignNodeRole` consumes, leaving the core delta pure. This test is that fix's stated
/// acceptance criterion, so it runs ENABLED — a green run here is the end-to-end proof that a
/// labelled worker reaches role assignment through the real projector path.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiSourceCommunitySmokeTest {
    private static final Logger log = LoggerFactory.getLogger(MultiSourceCommunitySmokeTest.class);

    private static final int INITIAL_CORES = 5;
    private static final String SOURCE_A = "src-a";
    private static final String SOURCE_B = "src-b";
    private static final String COMMUNITY_A = SOURCE_A + "-w-0";
    private static final String COMMUNITY_B = SOURCE_B + "-w-0";
    private static final String DEFAULT_COMMUNITY = "default-w-0";

    private static final int BASE_PORT = 22650;
    private static final int BASE_MGMT_PORT = 22750;
    private static final int BASE_APP_HTTP_PORT = 22850;

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MINT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofMillis(500);

    private EmberCluster cluster;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(INITIAL_CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "msrc");
        // #491 raised SWIM/membership timeouts: nine in-JVM nodes contend for the same machine's
        // cores, and the default windows read scheduling stalls as SUSPECT churn.
        cluster.withRaisedSwimTimeouts();
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        // The leader's BootstrapModule auto-seeds committed ClusterConfig.coreCount = 5; until it
        // lands, joiners would be promoted to core instead of assigned WORKER.
        await().atMost(FORM_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> committedCoreCount().filter(count -> count == INITIAL_CORES).isPresent());
        log.info("MSRC-SMOKE: {}-core cluster formed, committed cap={}", INITIAL_CORES, committedCoreCount().or(-1));
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    void twoWorkerSources_mintTwoDistinctCommunities_andNoDefaultFallback() {
        addWorkerAndSettle(1, SOURCE_A);
        addWorkerAndSettle(2, SOURCE_A);
        addWorkerAndSettle(3, SOURCE_B);
        addWorkerAndSettle(4, SOURCE_B);

        await().atMost(MINT_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> communityValue(COMMUNITY_A).isPresent() && communityValue(COMMUNITY_B).isPresent());

        assertThat(communityValue(COMMUNITY_A).map(CommunityValue::sourceName).or(""))
            .as("community %s must record its minting source", COMMUNITY_A)
            .isEqualTo(SOURCE_A);
        assertThat(communityValue(COMMUNITY_B).map(CommunityValue::sourceName).or(""))
            .as("community %s must record its minting source", COMMUNITY_B)
            .isEqualTo(SOURCE_B);
        // The label round-trip proof: unlabeled joins mint default-w-0 (CommunityFormationProbeTest
        // demonstrates exactly that), so an existing default community here would mean the source
        // label was dropped somewhere and the two named communities above were minted by luck.
        assertThat(communityValue(DEFAULT_COMMUNITY).isPresent())
            .as("no worker may fall back to the default source — the label must round-trip")
            .isFalse();
        assertThat(countedCores())
            .as("the core count must stay at the cap — no worker was promoted to core")
            .isEqualTo(INITIAL_CORES);
    }

    // ----- worker join, mirroring CommunityFormationProbeTest's sequential settle -----

    private void addWorkerAndSettle(int index, String source) {
        var expectedNodeCount = INITIAL_CORES + index;

        cluster.addNode(Map.of(NodeInfo.LABEL_ROLE, "worker", NodeInfo.LABEL_SOURCE, source))
               .await()
               .onSuccess(nodeId -> log.info("MSRC-SMOKE: worker {}/4 (source={}) joined as {}",
                                             index, source, nodeId.id()))
               .onFailure(cause -> {
                   throw new AssertionError("worker " + index + " (source " + source + ") failed to join: "
                                            + cause.message());
               });
        await().atMost(SETTLE_TIMEOUT)
               .pollInterval(POLL)
               .until(() -> cluster.currentLeader().isPresent()
                            && countedCores() == INITIAL_CORES
                            && cluster.nodeCount() == expectedNodeCount);
        log.info("MSRC-SMOKE: after worker {}/4 countedCores={} nodeCount={}",
                 index, countedCores(), cluster.nodeCount());
    }

    // ----- committed-state reads off the leader KV store (the probe's accessors) -----

    private Option<Integer> committedCoreCount() {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(ClusterConfigKey.CURRENT))
                                .filter(ClusterConfigValue.class::isInstance)
                                .map(ClusterConfigValue.class::cast)
                                .map(ClusterConfigValue::coreCount);
    }

    private Option<CommunityValue> communityValue(String communityId) {
        return leaderOrAnyNode().flatMap(node -> node.kvStore().get(CommunityKey.communityKey(communityId)))
                                .filter(CommunityValue.class::isInstance)
                                .map(CommunityValue.class::cast);
    }

    private int countedCores() {
        return leaderOrAnyNode().map(node -> node.membershipFsm().coreCountedMembers().size()).or(0);
    }

    private Option<AetherNode> leaderOrAnyNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .orElse(() -> Option.from(cluster.allNodes().stream().findFirst()));
    }
}
