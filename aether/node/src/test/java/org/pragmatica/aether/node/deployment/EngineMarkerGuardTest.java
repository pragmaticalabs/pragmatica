// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.deployment.EngineMarkerGuard.State;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// W-29 (phase-3 design 9.2, 8.2): every row of the engine marker table refuses the node ITSELF, on positive evidence only, after one WARN
/// window measured from an observed committed `LeaderValue`; both-absent waits and never refuses; a node whose engine disagrees activates
/// neither engine's FSMs during its window. MT-24 (a refusal on absence alone, or before the window), MT-29 (both-absent read as LEGACY),
/// MT-30 (no activation gate).
class EngineMarkerGuardTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId MINTER = NodeId.nodeId("core-3").unwrap();
    private static final long WINDOW_MS = 13_000L;
    private static final long NOW = 1_760_000_000_000L;
    private static final DeploymentEngineValue FSM2_MARKER = new DeploymentEngineValue(DeploymentEngine.FSM2, MINTER, NOW - 5_000L);
    private static final DeploymentEngineValue LEGACY_MARKER = new DeploymentEngineValue(DeploymentEngine.LEGACY, MINTER, NOW - 5_000L);

    private final ManualClock clock = new ManualClock();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);
    private final List<String> exits = Collections.synchronizedList(new ArrayList<>());
    private final List<String> activations = Collections.synchronizedList(new ArrayList<>());
    private Option<DeploymentEngineValue> marker = Option.none();
    private boolean incarnation;
    private Option<LeaderValue> leader = Option.some(new LeaderValue(MINTER, 1L));

    private EngineMarkerGuard guard(DeploymentEngine engine) {
        return EngineMarkerGuard.engineMarkerGuard(engine, SELF, WINDOW_MS, new EngineMarkerGuard.Reader() {
            @Override
            public Option<DeploymentEngineValue> marker() {
                return marker;
            }

            @Override
            public boolean incarnationPresent() {
                return incarnation;
            }

            @Override
            public Option<LeaderValue> leader() {
                return leader;
            }
        }, clock, () -> NOW, sink, exits::add);
    }

    /// The sink hands each warning to its publisher on another thread, so the list is read after the hand-off settles.
    private List<OperatorWarning> mismatchWarnings() {
        quiesce();

        return warnings.stream().filter(w -> w.code() == OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH).toList();
    }

    /// The sink publishes in order on its own thread: a sentinel offered now is published after every warning offered before it.
    private void quiesce() {
        var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.SWIM_KILL_GATE_HELD, "sentinel", "sentinel");

        sink.accept(sentinel);
        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.contains(sentinel));
        warnings.remove(sentinel);
    }

    @Nested
    class Fsm2Node {
        @Test
        void anFsm2Marker_opensTheGate_andRunsWhatWasParked() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpen("activate", () -> activations.add("activate"));
            assertThat(activations).as("closed until the marker is read").isEmpty();

            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.OPEN);
            assertThat(activations).containsExactly("activate");
            assertThat(mismatchWarnings()).isEmpty();
            assertThat(exits).isEmpty();
        }

        /// The marker names LEGACY: the node refuses itself, after one WARN window, with the evidence and the recovery in the text.
        @Test
        void aLegacyMarker_refusesTheNode_afterOneWarnWindow() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.MISMATCH_WINDOW);
            assertThat(mismatchWarnings()).as("one WARN opens the window").hasSize(1);
            assertThat(exits).as("not before the window ends").isEmpty();
            var text = mismatchWarnings().getFirst().message();

            assertThat(text).contains("configured for FSM2").contains("engine=LEGACY").contains(MINTER.id()).contains("fresh NodeId").contains("re-genesis");
            clock.advance(WINDOW_MS - 1);
            assertThat(exits).isEmpty();
            clock.advance(1);

            assertThat(guard.state()).isEqualTo(State.REFUSED);
            assertThat(exits).as("the node refuses itself, once").hasSize(1);
            assertThat(mismatchWarnings()).as("the WARN was not repeated").hasSize(1);
        }

        /// G-2: an incarnation with no marker is positive, durable evidence of a pre-phase-3 (LEGACY) cluster.
        @Test
        void anIncarnationWithoutAMarker_isAPrePhase3Cluster_andRefusesTheNode() {
            var guard = guard(DeploymentEngine.FSM2);

            incarnation = true;
            guard.evaluate();
            clock.advance(WINDOW_MS);

            assertThat(mismatchWarnings().getFirst().message()).contains("marker absent, incarnation present");
            assertThat(guard.state()).isEqualTo(State.REFUSED);
            assertThat(exits).hasSize(1);
        }

        /// G-2 / MT-29: with both keys absent genesis is still running. The node waits: no WARN, no window, no exit, however long it takes.
        @Test
        void bothKeysAbsent_waitsForever_neverWarnsNeverRefuses() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpen("activate", () -> activations.add("activate"));
            guard.evaluate();
            clock.advance(10 * WINDOW_MS);
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.WAITING);
            assertThat(mismatchWarnings()).isEmpty();
            assertThat(exits).isEmpty();
            assertThat(activations).isEmpty();
        }

        /// The review's G-2 false-positive case: an FSM2 node that joins during the mint's backoff, with both keys absent, never refuses; when the
        /// mint lands it opens and runs what it parked.
        @Test
        void aLateGenesisMember_neverRefuses_andOpensWhenTheMintLands() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpen("activate", () -> activations.add("activate"));
            guard.evaluate();
            clock.advance(5 * WINDOW_MS);
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.OPEN);
            assertThat(activations).containsExactly("activate");
            assertThat(exits).isEmpty();
            assertThat(mismatchWarnings()).isEmpty();
        }

        /// The window is measured from an observed committed LeaderValue, never from "runtime ready": a mismatch seen with no leader committed
        /// starts nothing.
        @Test
        void theWindowStartsAtAnObservedLeader_notBefore() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            leader = Option.none();
            guard.evaluate();
            clock.advance(3 * WINDOW_MS);
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.MISMATCH_UNTIMED);
            assertThat(mismatchWarnings()).isEmpty();
            assertThat(exits).isEmpty();

            leader = Option.some(new LeaderValue(MINTER, 2L));
            guard.evaluate();
            assertThat(guard.state()).isEqualTo(State.MISMATCH_WINDOW);
            clock.advance(WINDOW_MS - 1);
            assertThat(exits).as("the window runs from the observed leader").isEmpty();
            clock.advance(1);
            assertThat(exits).hasSize(1);
        }

        /// A mismatch that clears inside the window (a restore replaced the marker) cancels the exit.
        @Test
        void aMismatchThatClearsInsideTheWindow_cancelsTheExit() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            clock.advance(WINDOW_MS / 2);
            marker = Option.some(FSM2_MARKER);
            guard.evaluate();
            clock.advance(WINDOW_MS);

            assertThat(guard.state()).isEqualTo(State.OPEN);
            assertThat(exits).isEmpty();
        }

        /// MT-24 / MT-30: a node that wins an election with a mismatching marker writes nothing before it exits. Its activation is parked, then
        /// dropped with the refusal.
        @Test
        void aMismatchingNode_activatesNothing_beforeItExits() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            guard.whenOpen("leader", () -> activations.add("leader-activate"));
            clock.advance(WINDOW_MS);
            guard.whenOpen("leader", () -> activations.add("late"));

            assertThat(activations).isEmpty();
            assertThat(guard.state()).isEqualTo(State.REFUSED);
        }

        @Test
        void aParkedActivation_isReplacedByALaterOneOfTheSameName() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpen("leader", () -> activations.add("activate"));
            guard.whenOpen("leader", () -> activations.add("deactivate"));
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(activations).as("level-triggered: the latest wins").containsExactly("deactivate");
        }
    }

    @Nested
    class LegacyNode {
        @Test
        void noMarkerAtAll_runs() {
            var guard = guard(DeploymentEngine.LEGACY);

            guard.whenOpen("activate", () -> activations.add("activate"));
            guard.evaluate();

            assertThat(guard.isOpen()).isTrue();
            assertThat(activations).containsExactly("activate");
        }

        @Test
        void aLegacyMarker_andAPrePhase3Cluster_run() {
            var guard = guard(DeploymentEngine.LEGACY);

            incarnation = true;
            guard.evaluate();
            assertThat(guard.isOpen()).as("incarnation present, marker absent").isTrue();
            marker = Option.some(LEGACY_MARKER);
            guard.evaluate();
            assertThat(guard.isOpen()).as("marker LEGACY").isTrue();
            assertThat(mismatchWarnings()).isEmpty();
        }

        /// An FSM2 marker: the LEGACY node refuses itself after the window, and while the window runs it activates neither engine's FSMs (a
        /// LEGACY newcomer in an FSM2 cluster would otherwise write `NodeArtifactKey` goals for one window).
        @Test
        void anFsm2Marker_refusesTheNode_andGatesItsActivationDuringTheWindow() {
            var guard = guard(DeploymentEngine.LEGACY);

            guard.whenOpen("leader", () -> activations.add("before-the-marker"));
            assertThat(activations).as("open until a mismatching marker is read").containsExactly("before-the-marker");
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();
            guard.whenOpen("leader", () -> activations.add("during-the-window"));

            assertThat(guard.state()).isEqualTo(State.MISMATCH_WINDOW);
            assertThat(activations).as("MT-30: the mismatch activation gate").containsExactly("before-the-marker");
            clock.advance(WINDOW_MS);
            assertThat(exits).hasSize(1);
            assertThat(mismatchWarnings().getFirst().message()).contains("configured for LEGACY").contains("engine=FSM2");
        }
    }
}
