// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// v-2174b delta pass at b8d1be1375. The F11 fix joins a second worker's directive into the first worker's minting DIRECTIVE draft
/// (`Directory.commit`), but that draft carries the FIRST worker as its node, so `Directory.directiveInFlight(second)` is false and
/// `Joins.trackIfQueued` does not keep the second worker in `pendingJoins`. When the joint write fails or is lost, only the first worker
/// is derived again.
class V2174bDeltaTest {
    private static MbWorld twoWorkers(org.pragmatica.consensus.NodeId w1, org.pragmatica.consensus.NodeId w2) {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withCommunityPolicies(Map.of())
                                           .withMemberSource(Map.of(w1, "east", w2, "east")))
                      .activate();
    }

    @Test
    void twoWorkerJoins_inOneDrain_whoseJointDirectiveWriteFailed_bothGetADirective() {
        var w1 = node("worker-1");
        var w2 = node("worker-2");
        var w = twoWorkers(w1, w2);

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliverTogether(new ClusterInput.WorkerJoin(w1, w.admit(w1, 21L)),
                            new ClusterInput.WorkerJoin(w2, w.admit(w2, 22L)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("precondition: one joint directive write").hasSize(1);
        assertThat(w.h.submits(SubmitKind.DIRECTIVE).getFirst().transaction().mutations()).as("precondition: it carries both directives and the mint")
                                                                                        .hasSize(3);
        w.h.held.clear();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(120_000L);
        assertThat(w.present(new ActivationDirectiveKey(w1))).as("control: the draft's own node is derived again").isTrue();
        assertThat(w.present(new ActivationDirectiveKey(w2))).as("the joined worker is derived again too").isTrue();
    }

    /// NT-21 / C12: a committed FAILED schema record routes `ActivationBlocked` + audit. Round 4 made a suspended leader silent
    /// (`Schemas.blocked`), but nothing announces the record after the CI-16 resume, so a FAILED that lands during a witness-stale window
    /// is never reported by anyone (the leader is still this node).
    @Test
    void activationBlocked_committedWhileSuspended_isAnnouncedAfterTheResume() {
        var h = LcSupport.leader(org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                 0.5d,
                                 CORE_1);

        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.seed(new org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey("ds"),
               new org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue("ds",
                                                                                     1,
                                                                                     "m1",
                                                                                     org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus.FAILED,
                                                                                     "g:a:1",
                                                                                     blueprintId("org.example:app:1.0.0"),
                                                                                     2,
                                                                                     FsmHarness.START_WALL));
        h.drain();
        assertThat(h.model().schemas.containsKey("ds")).as("precondition: indexed while suspended").isTrue();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(30_000L);
        assertThat(h.effects(ClusterEffect.Route.class)
                    .stream()
                    .filter(route -> route.event() instanceof RoutedEvent.ActivationBlocked)
                    .count()).as("announced once after the resume").isEqualTo(1L);
    }

    @Test
    void twoWorkerJoins_inOneDrain_whoseJointDirectiveWriteWasLost_bothGetADirective() {
        var w1 = node("worker-1");
        var w2 = node("worker-2");
        var w = twoWorkers(w1, w2);

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.deliverTogether(new ClusterInput.WorkerJoin(w1, w.admit(w1, 21L)),
                            new ClusterInput.WorkerJoin(w2, w.admit(w2, 22L)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("precondition: one joint directive write").hasSize(1);
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(180_000L);
        assertThat(w.present(new ActivationDirectiveKey(w1))).as("control: the draft's own node is derived again").isTrue();
        assertThat(w.present(new ActivationDirectiveKey(w2))).as("the joined worker is derived again too").isTrue();
    }
}
