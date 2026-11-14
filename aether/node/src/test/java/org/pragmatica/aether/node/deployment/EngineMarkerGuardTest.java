// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

    private List<OperatorWarning> matchedWarnings() {
        quiesce();

        return warnings.stream().filter(w -> w.code() == OperatorWarningCode.DEPLOYMENT_ENGINE_MATCHED).toList();
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

        /// A second look at the same mismatch inside the window changes nothing: the window is measured from the first observation, so the node does
        /// not exit before it ends and does not WARN again.
        @Test
        void aRepeatedEvaluation_insideTheWindow_neitherRefusesNorWarnsAgain() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            clock.advance(WINDOW_MS / 2);
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.MISMATCH_WINDOW);
            assertThat(exits).isEmpty();
            assertThat(mismatchWarnings()).hasSize(1);

            clock.advance(WINDOW_MS / 2);

            assertThat(guard.state()).isEqualTo(State.REFUSED);
            assertThat(exits).hasSize(1);
        }

        /// A mismatch that cleared and came back is a NEW window: the exit armed for the first one was cancelled when it cleared, so it cannot cut
        /// the second short.
        @Test
        void aMismatchThatClearsAndReturns_startsANewWindow_theOldExitIsGone() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            clock.advance(WINDOW_MS / 2);
            marker = Option.some(FSM2_MARKER);
            guard.evaluate();
            clock.advance(100);
            marker = Option.some(LEGACY_MARKER);
            guard.evaluate();

            clock.advance(WINDOW_MS / 2 - 100);

            assertThat(exits).as("the first window's deadline passed; the second one is still running").isEmpty();
            assertThat(guard.state()).isEqualTo(State.MISMATCH_WINDOW);

            clock.advance(WINDOW_MS / 2 + 100);

            assertThat(exits).as("the second window ended").hasSize(1);
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

        @Test
        void aParkedActivation_keepsAParkedDeactivationAheadOfIt_soTheFsmReincarnates() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();
            marker = Option.none();
            incarnation = false;
            guard.evaluate();
            assertThat(guard.state()).isEqualTo(State.WAITING);

            guard.whenOpen("leader", () -> activations.add("deactivate"));
            guard.whenOpenAfterParked("leader", () -> activations.add("activate"));
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(activations).as("#3253: Deactivate then Activate, in order").containsExactly("deactivate", "activate");
        }

        @Test
        void aLaterDeactivation_supersedesAParkedDeactivateActivatePair() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpen("leader", () -> activations.add("deactivate-1"));
            guard.whenOpenAfterParked("leader", () -> activations.add("activate"));
            guard.whenOpen("leader", () -> activations.add("deactivate-2"));
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(activations).as("level-triggered: the latest Deactivate wins").containsExactly("deactivate-2");
        }

        @Test
        void aParkedActivation_withNothingParkedUnderTheName_runsAlone() {
            var guard = guard(DeploymentEngine.FSM2);

            guard.whenOpenAfterParked("leader", () -> activations.add("activate"));
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            guard.evaluate();

            assertThat(activations).containsExactly("activate");
        }
    }

    @Nested
    class Actions {
        /// A running action never makes a deciding thread wait: the guard sits on the default (LEGACY) path of every gated hook, and `evaluate`
        /// is called from the KV dispatch, so it must not queue behind legacy work.
        @Test
        void evaluate_neverWaitsForARunningAction() throws Exception {
            var guard = guard(DeploymentEngine.LEGACY);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var runner = Thread.ofPlatform().start(() -> guard.whenOpen("slow", () -> hold(entered, release)));

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                CompletableFuture.runAsync(guard::evaluate).get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                runner.join(5_000);
            }
        }

        /// What was parked runs, in order, before anything admitted after the opening: the later action waits for the drain, however long the drain
        /// takes.
        @Test
        void anActionAdmittedAfterTheOpening_neverOvertakesTheParkedOnes() throws Exception {
            var guard = guard(DeploymentEngine.FSM2);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);

            guard.whenOpen("first", () -> {
                activations.add("parked-start");
                hold(entered, release);
                activations.add("parked-end");
            });
            marker = Option.some(FSM2_MARKER);
            incarnation = true;
            var opener = Thread.ofPlatform().start(guard::evaluate);

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var later = Thread.ofPlatform().start(() -> guard.whenOpen("second", () -> activations.add("later")));

            Thread.sleep(300);
            assertThat(activations).as("the later action waits for the drain").containsExactly("parked-start");
            release.countDown();
            opener.join(5_000);
            later.join(5_000);

            assertThat(activations).containsExactly("parked-start", "parked-end", "later");
        }

        /// P2 (v-2643 finding 2), deterministic: an opening lands while the admitting thread runs drained work, and drains an older parked
        /// activation. The newer action is decided only once nothing drained is left ahead of it, so the older one runs first. RED before the fix
        /// (`runDrained` then `admitted` as two steps): the newer action ran, and the older one after it.
        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void aDrainedActionThatReopensTheGate_runsTheOlderParkedActionBeforeTheNewerOne() throws Exception {
            var marker = new AtomicReference<>(Option.some(FSM2_MARKER));
            var guard = sharedMarkerGuard(marker);
            var order = Collections.synchronizedList(new ArrayList<String>());
            var openers = new CopyOnWriteArrayList<Thread>();

            guard.evaluate();
            guard.whenOpen("hold", () -> {
                parkAndReopen(guard, marker, openers, "w", () -> parkAndReopen(guard, marker, openers, "leader", () -> order.add("older")));
                guard.whenOpen("leader", () -> order.add("newer"));
            });
            for (var opener : openers) {
                opener.join(10_000);
            }

            assertThat(openers).as("both reopenings ran on their own threads").hasSize(2);
            assertThat(order).as("the older parked activation runs before the newer admitted action").containsExactly("older", "newer");
        }

        /// P2 (v-2643 finding 2), the verifier's probe: the same placement reached through lock contention, repeated until the admitting thread
        /// wins the lock twenty times. Verifier control: 20 inversions of 20 before the fix, 0 after.
        @Test
        @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void guard_parkedActivation_neverRunsAfterANewerAdmittedOne() throws Exception {
            int inversions = 0;
            int aWon = 0;
            for (int i = 0; i < 200 && aWon < 20; i++) {
                var order = Collections.synchronizedList(new ArrayList<String>());
                var marker = new AtomicReference<>(Option.some(FSM2_MARKER));
                var guard = sharedMarkerGuard(marker);
                guard.evaluate();
                assertThat(guard.state()).isEqualTo(State.OPEN);
                var ranW = new AtomicReference<>("");
                var openerB = new AtomicReference<Option<Thread>>(Option.none());
                Runnable w = () -> {
                    ranW.set(Thread.currentThread().getName());
                    // close the gate, park Y (older), reopen from another thread whose decide() lands now
                    marker.set(Option.some(LEGACY_MARKER));
                    guard.evaluate();
                    guard.whenOpen("leader", () -> order.add("Y-activate(older)"));
                    marker.set(Option.some(FSM2_MARKER));
                    openerB.set(Option.some(Thread.ofPlatform().name("B").start(guard::evaluate)));
                    awaitOpen(guard);
                    // give B time to pass decided() and block on the lock
                    pause(20);
                };
                var latch = new CountDownLatch(1);
                var parkedW = new CountDownLatch(1);
                // H holds the lock while W is parked; A queues on the lock FIRST, then C's evaluate drains W and queues behind A
                var h = Thread.ofPlatform().name("H").start(() -> guard.whenOpen("hold", () -> {
                    marker.set(Option.some(LEGACY_MARKER));
                    guard.evaluate();
                    guard.whenOpen("w", w);
                    parkedW.countDown();
                    try {
                        latch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                parkedW.await(5, TimeUnit.SECONDS);
                var a = Thread.ofPlatform().name("A").start(() -> guard.whenOpen("leader", () -> order.add("X-deactivate(newer)")));
                Thread.sleep(30);
                marker.set(Option.some(FSM2_MARKER));
                var c = Thread.ofPlatform().name("C").start(guard::evaluate);
                Thread.sleep(30);
                latch.countDown();
                c.join(10_000);
                h.join(10_000);
                a.join(10_000);
                openerB.get().onPresent(this::joinQuietly);
                if ("A".equals(ranW.get())) {
                    aWon++;
                    if (order.indexOf("X-deactivate(newer)") < order.indexOf("Y-activate(older)")) {
                        inversions++;
                    }
                }
            }
            assertThat(aWon).as("the interleaving was reached").isGreaterThan(0);
            assertThat(inversions).as("older parked activation ran after a newer admitted one, in %d runs that reached the interleaving", aWon).isZero();
        }

        /// Closes the gate, parks `action` under `name`, and reopens it from a new thread, returning once that thread's decision has drained it.
        private void parkAndReopen(EngineMarkerGuard guard,
                                   AtomicReference<Option<DeploymentEngineValue>> marker,
                                   List<Thread> openers,
                                   String name,
                                   Runnable action) {
            marker.set(Option.some(LEGACY_MARKER));
            guard.evaluate();
            assertThat(guard.isOpen()).as("the gate closed").isFalse();
            guard.whenOpen(name, action);
            marker.set(Option.some(FSM2_MARKER));
            openers.add(Thread.ofPlatform().start(guard::evaluate));
            awaitOpen(guard);
        }

        private void awaitOpen(EngineMarkerGuard guard) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!guard.isOpen() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(guard.isOpen()).as("the reopening decided within 5 s").isTrue();
        }

        private void joinQuietly(Thread thread) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void pause(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private EngineMarkerGuard sharedMarkerGuard(AtomicReference<Option<DeploymentEngineValue>> marker) {
            return EngineMarkerGuard.engineMarkerGuard(DeploymentEngine.FSM2, SELF, WINDOW_MS, new EngineMarkerGuard.Reader() {
                @Override
                public Option<DeploymentEngineValue> marker() {
                    return marker.get();
                }

                @Override
                public boolean incarnationPresent() {
                    return true;
                }

                @Override
                public Option<LeaderValue> leader() {
                    return Option.some(new LeaderValue(MINTER, 1L));
                }
            }, new ManualClock(), () -> NOW, sink, exits::add);
        }

        private void hold(CountDownLatch entered, CountDownLatch release) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    /// D.6 row 1 (know 7cc9e79f61): `deployment-engine-mismatch` is closed by `deployment-engine-matched`, same subject (the node), when its
    /// window ends without a refusal, and with the reason `node-refused-itself` when the node refuses itself (its subject leaves).
    @Nested
    class D6Recovery {
        /// Red when: a mismatch that clears inside the window raises no `deployment-engine-matched` for the node, or raises it twice.
        @Test
        void aMismatchThatClearsInsideTheWindow_closesTheWarning() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            assertThat(mismatchWarnings()).as("the opener").hasSize(1);
            assertThat(matchedWarnings()).as("not before the condition ends").isEmpty();
            marker = Option.some(FSM2_MARKER);
            guard.evaluate();
            guard.evaluate();

            assertThat(matchedWarnings()).hasSize(1);
            assertThat(matchedWarnings().getFirst().subject()).isEqualTo(SELF.id());
            assertThat(matchedWarnings().getFirst().message()).doesNotContain(EngineMarkerGuard.REFUSED_ITSELF);
        }

        /// Red when: a window that ends because the cluster's engine is undecided again leaves the warning open.
        @Test
        void aMismatchThatTurnsUndecided_closesTheWarning() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            marker = Option.none();
            incarnation = false;
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.WAITING);
            assertThat(matchedWarnings()).hasSize(1);
        }

        /// Subject gone. Red when: the node that refuses itself exits without closing its warning, or the close lacks the reason.
        @Test
        void aRefusal_closesTheWarning_withTheReasonNodeRefusedItself() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            guard.evaluate();
            clock.advance(WINDOW_MS);

            assertThat(exits).hasSize(1);
            assertThat(matchedWarnings()).hasSize(1);
            assertThat(matchedWarnings().getFirst().subject()).isEqualTo(SELF.id());
            assertThat(matchedWarnings().getFirst().message()).contains(EngineMarkerGuard.REFUSED_ITSELF);
        }

        /// Red when: a recovery is raised for a window that was never reported (no leader observed yet), or for a node that never mismatched.
        @Test
        void anUnreportedMismatch_orAMatchingNode_raisesNoRecovery() {
            var guard = guard(DeploymentEngine.FSM2);

            marker = Option.some(LEGACY_MARKER);
            incarnation = true;
            leader = Option.none();
            guard.evaluate();
            marker = Option.some(FSM2_MARKER);
            guard.evaluate();

            assertThat(guard.state()).isEqualTo(State.OPEN);
            assertThat(mismatchWarnings()).isEmpty();
            assertThat(matchedWarnings()).isEmpty();
        }
    }
}
