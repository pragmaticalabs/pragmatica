// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import java.util.List;

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

    // -- the FSM2 submission point ------------------------------------------------------------------------------------------
    private static List<KVCommand<AetherKey>> write(NodeId node,
                                                                                                                                         Artifact artifact,
                                                                                                                                         AetherValue.SliceObservedState state) {
        var key = AetherKey.SliceObservedKey.sliceObservedKey(node, artifact);
        var row = new AetherValue.SliceObservedValue(state,
                                                                                        1L,
                                                                                        new AetherValue.Generation(1L, 1L),
                                                                                        new AetherValue.ProcessIncarnation(1L, 5L),
                                                                                        Option.none(),
                                                                                        false,
                                                                                        0,
                                                                                        List.of(),
                                                                                        1L);

        return List.of(new KVCommand.Put<>(key, row));
    }

    @Test
    void fsm2_neverArmed_answersEverySubmission() {
        var active = AetherValue.SliceObservedState.ACTIVE;

        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 1, 0, write(NODE, ARTIFACT, active))).isFalse();
    }

    @Test
    void fsm2_armed_takesOnlyTheFirstOwnActiveSubmission_notItsProbe() {
        var active = AetherValue.SliceObservedState.ACTIVE;
        var loaded = AetherValue.SliceObservedState.LOADED;

        ActivationWriteFault.arm(NODE, ARTIFACT);

        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 1, 0, write(NODE, ARTIFACT, active))).as("attempt 1, the write's own submission").isTrue();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 1, 1, write(NODE, ARTIFACT, active))).as("a probe resubmission of the same attempt").isFalse();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 2, 0, write(NODE, ARTIFACT, active))).as("a later attempt").isFalse();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 1, 0, write(NODE, ARTIFACT, loaded))).as("not an ACTIVE write").isFalse();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(OTHER_NODE, ARTIFACT, 1, 0, write(OTHER_NODE, ARTIFACT, active))).as("another node").isFalse();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, OTHER_ARTIFACT, 1, 0, write(NODE, OTHER_ARTIFACT, active))).as("another artifact").isFalse();
        assertThat(ActivationWriteFault.leavesUnansweredFsm2(NODE, ARTIFACT, 1, 0, write(OTHER_NODE, ARTIFACT, active))).as("a row of another node in the batch").isFalse();
        assertThat(ActivationWriteFault.unansweredSubmissions()).isEqualTo(1);
    }
}
