// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.ActivationTimeReached;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// F5b (pragmatica #2765; phase-3 design 8.2 D-10; spec 1.6.1 ControlLoop row): under FSM2 the autoscaler's slice-state
/// source is the node's observed row (`SliceObservedKey`) shown by the state feed (F5a), not `NodeArtifactKey`, which FSM2 never writes. The seams that
/// record what the loop learned are the scaling gate ([ControlLoopContext#anySliceInProgress] with
/// [ControlLoopContext#describeInProgressSlice]) and the slice cooldown an ACTIVE transition marks.
class ControlLoopObservedSourceTest {
    private static final NodeId SELF = NodeId.nodeId("ctl-loop-observed").unwrap();
    private static final NodeId HOST = NodeId.nodeId("ctl-loop-observed-host").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:observed:1.0.0").unwrap();
    private static final long TOKEN = 7L;
    private static final SliceObservedKey KEY = new SliceObservedKey(HOST, ARTIFACT);

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

    @BeforeEach
    void setUp() {
        loop.activate();
        loop.fsm().dispatch(new ActivationTimeReached());
        assertThat(loop.fsm().current()).as("precondition: evaluating").isInstanceOf(ControlLoopState.Evaluating.class);
    }

    @AfterEach
    void tearDown() {
        loop.stop();
    }

    /// Every shown observed state reaches the loop as its legacy counterpart. UNLOADED is never shown by the feed (the slice left the node),
    /// so it is not a row here. Each row is `state, in progress, described legacy state`.
    @Test
    void onObservedSlice_shownRow_reachesScalingGateAsLegacyState() {
        var expected = List.of(List.of("LOADING", "true", "LOADING"),
                               List.of("LOADED", "false", ""),
                               List.of("ACTIVATING", "true", "ACTIVATING"),
                               List.of("ACTIVE", "false", ""),
                               List.of("DEACTIVATING", "true", "DEACTIVATING"),
                               List.of("FAILED", "false", ""),
                               List.of("UNLOADING", "true", "UNLOADING"),
                               List.of("UNKNOWN", "false", ""));

        assertThat(expected.stream().map(row -> row.getFirst()).toList())
            .as("every shown observed state is covered")
            .containsExactlyInAnyOrder(Stream.of(SliceObservedState.values())
                                             .filter(state -> state != SliceObservedState.UNLOADED)
                                             .map(Enum::name)
                                             .toArray(String[]::new));
        expected.forEach(this::assertReachesGate);
    }

    private void assertReachesGate(List<String> row) {
        var state = SliceObservedState.valueOf(row.get(0));
        var inProgress = Boolean.parseBoolean(row.get(1));

        loop.onObservedSlice(KEY, row(state, TOKEN));

        assertThat(loop.ctx().anySliceInProgress()).as("scaling gate for %s", state).isEqualTo(inProgress);
        assertThat(loop.ctx().describeInProgressSlice().map(text -> text.endsWith(": " + row.get(2))).or(false))
            .as("described state for %s", state)
            .isEqualTo(inProgress);
        loop.onObservedSliceGone(KEY);
    }

    @Test
    void onObservedSlice_activeRow_marksSliceCooldown() {
        loop.onObservedSlice(KEY, row(SliceObservedState.ACTIVE, TOKEN));

        assertThat(loop.ctx().sliceCooldownsSnapshot()).containsOnlyKeys(ARTIFACT);
    }

    @Test
    void onObservedSliceGone_afterLoading_clearsState() {
        loop.onObservedSlice(KEY, row(SliceObservedState.LOADING, TOKEN));
        loop.onObservedSliceGone(KEY);

        assertThat(loop.ctx().anySliceInProgress()).isFalse();
    }

    static SliceObservedValue row(SliceObservedState state, long bootToken) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, bootToken),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      0L);
    }
}
