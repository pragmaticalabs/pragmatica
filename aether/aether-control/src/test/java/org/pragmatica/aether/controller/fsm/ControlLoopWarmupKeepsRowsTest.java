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

/// #3230: the observed-row feed shows each row once, and the loop records it whatever its FSM state. A row delivered after the node
/// became leader but before Warmup entry (Dormant, activation dispatched) must survive Warmup entry; the entry reset undoes the
/// previous term's cooldowns, not the live rows. Red if Warmup entry clears the slice states: the row is never replayed and the
/// scaling gate misses an in-progress slice.
class ControlLoopWarmupKeepsRowsTest {
    private static final NodeId SELF = NodeId.nodeId("ctl-warmup").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:warmup:1.0.0").unwrap();
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
    void rowDeliveredBeforeWarmupEntry_survivesWarmupEntry() {
        loop.onObservedSlice(KEY, row(SliceObservedState.LOADING));
        loop.activate();

        assertThat(loop.fsm().current()).as("Warmup entry ran").isInstanceOf(ControlLoopState.Warmup.class);
        assertThat(loop.ctx().anySliceInProgress()).as("the row delivered before Warmup entry").isTrue();
        loop.stop();
    }

    @Test
    void warmupEntry_stillUndoesThePreviousTermsCooldowns() {
        loop.onObservedSlice(KEY, row(SliceObservedState.ACTIVE));
        assertThat(loop.ctx().sliceCooldownsSnapshot()).containsOnlyKeys(ARTIFACT);
        loop.activate();

        assertThat(loop.ctx().sliceCooldownsSnapshot()).isEmpty();
        loop.stop();
    }

    private static SliceObservedValue row(SliceObservedState state) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 0, List.of(), 0L);
    }
}
