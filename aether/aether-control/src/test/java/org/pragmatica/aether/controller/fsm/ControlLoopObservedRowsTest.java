// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1, NT-95): the control loop tracks the per-node slice states of the rows the state feed shows.
/// Red if the loop reads only `NodeArtifactKey` rows.
class ControlLoopObservedRowsTest {
    private static final NodeId SELF = NodeId.nodeId("ctl-observed").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:observed:1.0.0").unwrap();
    private static final SliceObservedKey KEY = SliceObservedKey.sliceObservedKey(NodeId.nodeId("ctl-host").unwrap(), ARTIFACT);

    private final ControlLoop.ControlLoopAdapter loop = (ControlLoop.ControlLoopAdapter) ControlLoop.controlLoop(SELF,
                                                                                                               ControlLoopFsmTest.StubController.INSTANCE,
                                                                                                               new ControlLoopFsmTest.StubMetricsCollector(),
                                                                                                               Option.none(),
                                                                                                               new ControlLoopFsmTest.StubClusterNode(),
                                                                                                               FencedTargetWrites.fencedStore(SELF),
                                                                                                               TimeSpan.timeSpan(60_000)
                                                                                                                       .millis(),
                                                                                                               ControllerConfig.DEFAULT,
                                                                                                               _ -> {});

    @Test
    void shownRowInProgress_isTracked_andGoneClearsIt() {
        loop.onObservedSlice(KEY, row(SliceObservedState.ACTIVATING));

        assertThat(loop.ctx().anySliceInProgress()).isTrue();
        assertThat(loop.ctx().describeInProgressSlice().or("")).contains(ARTIFACT.asString());

        loop.onObservedSliceGone(KEY);

        assertThat(loop.ctx().anySliceInProgress()).isFalse();
    }

    @Test
    void shownRowSettled_replacesTheInProgressState() {
        loop.onObservedSlice(KEY, row(SliceObservedState.LOADING));
        loop.onObservedSlice(KEY, row(SliceObservedState.LOADED));

        assertThat(loop.ctx().anySliceInProgress()).isFalse();
    }

    private static SliceObservedValue row(SliceObservedState state) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 0, List.of(), 0L);
    }
}
