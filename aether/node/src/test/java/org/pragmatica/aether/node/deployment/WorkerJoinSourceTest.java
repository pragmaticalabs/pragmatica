// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #3172: a worker's first join is placed in its own source's community, never in a stray `default-w-0`, through the production assembly
/// (`MembershipInputs` over the seam poller and the real cluster interpreter). Membership tracks a worker, with its `source` label, before it
/// emits the worker's join, so the label is there to read when the join is delivered; the seam poller samples it only after the join, or on its
/// 5 s cadence (driven by a manual clock here, so it never ticks).
///
/// - The plain case: one worker, labelled just before its join. Before the fix the cluster FSM handled the join against a sample that predated
///   the label and placed the worker in `default-w-0`.
/// - The coalesced case: two workers join while the cluster consumer is held. The mailbox keeps one queued sample, so a sample taken for the
///   second join replaces any sample queued for the first and lands **behind** the first join; a fix that only samples ahead of each join
///   still places the first worker in `default-w-0`.
class WorkerJoinSourceTest {
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId OTHER_WORKER = NodeId.nodeId("worker-2").unwrap();
    private static final String SOURCE = "eu";
    private static final String COMMUNITY = SOURCE + "-w-0";
    private static final String CLUSTER_CONSUMER = "deploy-fsm-cluster";

    @Test
    void aWorkersFirstJoin_isPlacedInItsSourcesCommunity_notInDefault() {
        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(new ManualClock()).assembled()) {
            activate(harness);

            harness.memberSources.put(WORKER, SOURCE);
            harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> {}).accept(workerJoined(WORKER));

            harness.awaitThat(() -> directive(harness, WORKER).isPresent());
            assertThat(communityOf(harness, WORKER)).as("#3172: the join is placed with the source membership had when it emitted the join")
                                                    .isEqualTo(Option.some(COMMUNITY));
        }
    }

    @Test
    void twoWorkersJoiningWhileTheConsumerIsHeld_areBothPlacedInTheirSourcesCommunity() {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(new ManualClock()).assembled()) {
            harness.memberSourceHook = () -> holdTheClusterConsumer(held, release);
            harness.commitLeader();
            harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
            assertThat(await(held)).as("the cluster consumer is held while it samples the seams for Rebuild").isTrue();

            harness.memberSources.put(WORKER, SOURCE);
            harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> {}).accept(workerJoined(WORKER));
            harness.memberSources.put(OTHER_WORKER, SOURCE);
            harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> {}).accept(workerJoined(OTHER_WORKER));
            release.countDown();

            harness.awaitThat(() -> directive(harness, WORKER).isPresent() && directive(harness, OTHER_WORKER).isPresent());
            assertThat(communityOf(harness, WORKER)).as("#3172: the first join is not handled against a sample coalesced behind it")
                                                    .isEqualTo(Option.some(COMMUNITY));
            assertThat(communityOf(harness, OTHER_WORKER)).isEqualTo(Option.some(COMMUNITY));
        } finally {
            release.countDown();
        }
    }

    private static void activate(Fsm2Harness harness) {
        harness.commitLeader();
        harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
        harness.awaitThat(() -> harness.system.unwrap().clusterInterpreter().mailboxDepth() == 0 && harness.samples.get() >= 2);
    }

    private static void holdTheClusterConsumer(CountDownLatch held, CountDownLatch release) {
        if (Thread.currentThread().getName().equals(CLUSTER_CONSUMER) && held.getCount() > 0) {
            held.countDown();
            await(release);
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private static Option<ActivationDirectiveValue> directive(Fsm2Harness harness, NodeId node) {
        return Option.option(harness.store.snapshot().get(new ActivationDirectiveKey(node)))
                     .filter(ActivationDirectiveValue.class::isInstance)
                     .map(ActivationDirectiveValue.class::cast);
    }

    private static Option<String> communityOf(Fsm2Harness harness, NodeId node) {
        return directive(harness, node).map(ActivationDirectiveValue::communityId);
    }

    private static WorkerJoinDecision workerJoined(NodeId worker) {
        return WorkerJoinDecision.workerJoinDecision(worker, "worker", HlcTimestamp.ZERO);
    }
}
