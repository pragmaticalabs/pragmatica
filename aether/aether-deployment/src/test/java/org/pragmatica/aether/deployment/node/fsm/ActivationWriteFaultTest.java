// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// The #1117 tracer's fault must be inert unless armed, and once armed must take exactly the first submission of an
/// ACTIVE write by the one node for the one artifact. Node and artifact are unique to this class, so a concurrently
/// running test that writes ACTIVE can never be the one it takes.
class ActivationWriteFaultTest {
    private static final NodeId NODE = NodeId.nodeId("fault-test-node").unwrap();
    private static final NodeId OTHER_NODE = NodeId.nodeId("fault-test-other").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example.fault:fault-slice:1.0.0").unwrap();
    private static final Artifact OTHER_ARTIFACT = Artifact.artifact("org.example.fault:other-slice:1.0.0").unwrap();

    @AfterEach
    void disarm() {
        ActivationWriteFault.disarm();
    }

    @Test
    void leavesUnanswered_neverArmed_answersEverySubmission() {
        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 0)).isFalse();
    }

    @Test
    void leavesUnanswered_armed_takesTheFirstActiveSubmissionAndCountsIt() {
        ActivationWriteFault.arm(NODE, ARTIFACT);

        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 0)).isTrue();
        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 0)).isTrue();
        assertThat(ActivationWriteFault.unansweredSubmissions()).isEqualTo(2);
    }

    @Test
    void leavesUnanswered_armed_answersRetriesOtherStatesOtherNodesAndOtherArtifacts() {
        ActivationWriteFault.arm(NODE, ARTIFACT);

        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 1)).isFalse();
        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ROUTING, 0)).isFalse();
        assertThat(ActivationWriteFault.leavesUnanswered(OTHER_NODE, ARTIFACT, SliceState.ACTIVE, 0)).isFalse();
        assertThat(ActivationWriteFault.leavesUnanswered(NODE, OTHER_ARTIFACT, SliceState.ACTIVE, 0)).isFalse();
        assertThat(ActivationWriteFault.unansweredSubmissions()).isZero();
    }

    @Test
    void leavesUnanswered_disarmed_answersAgain_andArmingResetsTheCount() {
        ActivationWriteFault.arm(NODE, ARTIFACT);
        ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 0);
        ActivationWriteFault.disarm();

        assertThat(ActivationWriteFault.leavesUnanswered(NODE, ARTIFACT, SliceState.ACTIVE, 0)).isFalse();

        ActivationWriteFault.arm(NODE, ARTIFACT);

        assertThat(ActivationWriteFault.unansweredSubmissions()).isZero();
    }
}
