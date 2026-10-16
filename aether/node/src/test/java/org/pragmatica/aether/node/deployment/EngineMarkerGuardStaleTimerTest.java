// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.wiring.FsmTimers.TimerService;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// W-29 (the WARN window): a window timer whose body was already running when the window was cancelled (a clearing mismatch), and which
/// reaches markExpired only after a returning mismatch started a NEW window, refuses the node at once, before the new window has passed.
class EngineMarkerGuardStaleTimerTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId MINTER = NodeId.nodeId("core-3").unwrap();

    /// A scheduler whose cancel cannot stop a body that already started: the probe runs the captured body itself, after the cancel.
    private static final class Captured implements TimerService {
        final List<Runnable> bodies = new ArrayList<>();

        @Override
        public synchronized Runnable schedule(Runnable body, long delayMs) {
            bodies.add(body);

            return () -> {};
        }
    }

    private Option<DeploymentEngineValue> marker = Option.none();

    @Test
    void aTimerOfACancelledWindow_doesNotEndTheNextWindow() {
        var clock = new Captured();
        var exits = Collections.synchronizedList(new ArrayList<String>());
        var guard = EngineMarkerGuard.engineMarkerGuard(DeploymentEngine.FSM2, SELF, 13_000L, new EngineMarkerGuard.Reader() {
            @Override
            public Option<DeploymentEngineValue> marker() {
                return marker;
            }

            @Override
            public boolean incarnationPresent() {
                return true;
            }

            @Override
            public Option<LeaderValue> leader() {
                return Option.some(new LeaderValue(MINTER, 1L));
            }
        }, clock, () -> 0L, OperatorWarningSink.logOnly(), exits::add);

        marker = Option.some(new DeploymentEngineValue(DeploymentEngine.LEGACY, MINTER, 0L));
        guard.evaluate();
        marker = Option.some(new DeploymentEngineValue(DeploymentEngine.FSM2, MINTER, 0L));
        guard.evaluate();
        marker = Option.some(new DeploymentEngineValue(DeploymentEngine.LEGACY, MINTER, 0L));
        guard.evaluate();

        assertThat(clock.bodies).as("two windows were armed").hasSize(2);
        assertThat(guard.state()).isEqualTo(EngineMarkerGuard.State.MISMATCH_WINDOW);

        clock.bodies.getFirst().run();

        assertThat(exits).as("the first window's timer must not end the second window").isEmpty();
    }
}
