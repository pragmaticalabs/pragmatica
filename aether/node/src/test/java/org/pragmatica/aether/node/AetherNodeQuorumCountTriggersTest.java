// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;

/// #1853 — the quorum-loss detector's member count is DERIVED from the FSM, the installed voters and the
/// observed set, and the detector is re-evaluated on every change of every one of those inputs.
///
/// rc4 cloud run 9: the count was pushed, with no trigger for the voter install. When the last presence-sampler
/// edge preceded genesis installing the voters, the count was computed against an empty electorate (0), never
/// recomputed, and the node reported quorum=false forever on a cluster with consensus — and, because the arm latch
/// only sets on an evaluation that sees a quorate count, would never have self-fenced on a later quorum loss.
///
/// Each trigger test below isolates ONE input change and first asserts the detector is NOT armed, so a trigger
/// that is broken cannot be rescued by a neighbouring one.
class AetherNodeQuorumCountTriggersTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId D = new NodeId("node-d");
    private static final NodeId E = new NodeId("node-e");
    private static final List<NodeId> CORES = List.of(SELF, B, C, D, E);
    private static final int THRESHOLD = 3;

    private final AtomicReference<Option<VoterConfiguration>> voters = new AtomicReference<>(Option.none());
    private final List<Consumer<VoterConfiguration>> voterListeners = new ArrayList<>();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<QuorumLossIntent> intents = new ArrayList<>();
    private final AtomicReference<QuorumLossDetector> detectorRef = new AtomicReference<>();
    private final AtomicReference<MembershipFsm> fsmRef = new AtomicReference<>();
    private final MembershipFsm fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                                   System::currentTimeMillis,
                                                                   Long.MAX_VALUE,
                                                                   TimeSpan.timeSpan(40).millis());
    private final QuorumLossDetector detector = QuorumLossDetector.quorumLossDetector(membershipConfig(),
                                                                                       this::installedVoterCount,
                                                                                       this::derivedCount,
                                                                                       TimeSource.system(),
                                                                                       scheduler);

    AetherNodeQuorumCountTriggersTest() {
        fsmRef.set(fsm);
        detectorRef.set(detector);
        detector.setQuorumLossListener(intents::add);
        fsm.seed(Set.copyOf(CORES));
    }

    private int installedVoterCount() {
        return voters.get().map(v -> v.members().size()).or(0);
    }

    /// The production derivation, fed the same three inputs the assembly feeds it.
    private int derivedCount() {
        return AetherNode.derivedMemberCount(Option.option(fsmRef.get()), SELF, voters.get());
    }

    /// Voter source with `RabiaEngine.onVoterConfiguration` semantics: registers the listener and replays an
    /// already-installed configuration at once.
    private void voterSource(Consumer<VoterConfiguration> listener) {
        voterListeners.add(listener);
        voters.get().onPresent(listener);
    }

    /// The assembly's own transition wiring: `onFsmTransition` on every FSM transition.
    private void wireTransitionTrigger() {
        var journal = TransitionJournal.transitionJournal();

        fsm.onTransition(record -> AetherNode.onFsmTransition(journal, detectorRef, record));
    }

    /// genesis / §4 command / sync adoption: the engine stores the configuration, THEN calls its listeners.
    private void installVoters() {
        var configuration = VoterConfiguration.voterConfiguration(0, CORES).unwrap();

        voters.set(Option.some(configuration));
        voterListeners.forEach(listener -> listener.accept(configuration));
    }

    private void observeAllPeers() {
        List.of(B, C, D, E).forEach(peer -> fsm.onSwimHealthy(peer, 1L));
    }

    /// THE incident, deterministically: the last sampler edge lands before genesis installs the voters.
    @Test
    void samplerEdgeBeforeVoterInstall_quorumBecomesTrueOnceVotersInstalled() {
        AetherNode.wireQuorumCountTriggers(detector, fsm, this::voterSource);
        observeAllPeers();
        // The presence sampler's final UP edge: the assembly's nttReconcileTrigger. Electorate still empty.
        AetherNode.onNttReconcile(detectorRef, new AtomicReference<>());

        assertThat(detector.currentMemberCount()).as("arming: derived against an empty electorate").isZero();
        assertThat(detector.currentRequiredThreshold()).as("no electorate: threshold unknown").isZero();
        assertThat(detector.isArmed()).isFalse();

        installVoters();

        var snapshot = QuorumLossSnapshot.from(detector);

        assertThat(snapshot.strictMemberCount()).as("run 9 stayed at 0 here for 17 minutes").isEqualTo(5);
        assertThat(snapshot.requiredThreshold()).isEqualTo(THRESHOLD);
        assertThat(snapshot.belowThreshold()).isFalse();
        assertThat(snapshot.armed()).isTrue();
    }

    /// The safety half: the node that hit the race must still self-fence on a LATER real quorum loss.
    @Test
    void samplerEdgeBeforeVoterInstall_laterQuorumLoss_selfFences() {
        AetherNode.wireQuorumCountTriggers(detector, fsm, this::voterSource);
        wireTransitionTrigger();
        observeAllPeers();
        AetherNode.onNttReconcile(detectorRef, new AtomicReference<>());
        installVoters();
        assertThat(detector.isArmed()).as("arming: the race was survived").isTrue();

        List.of(B, C, D).forEach(peer -> fsm.onSwimSuspect(peer, 2L));

        assertThat(detector.currentMemberCount()).isEqualTo(2);
        assertThat(detector.isBelowThreshold()).isTrue();
        assertThat(scheduler.pendingCount()).as("the below-threshold window opened").isEqualTo(1);

        scheduler.fireAll();

        assertThat(intents).as("below threshold past the window: drain intent").hasSize(1);
        assertThat(intents.getFirst().observedLocalQuorumCount()).isEqualTo(2);
        assertThat(intents.getFirst().requiredThreshold()).isEqualTo(THRESHOLD);
    }

    /// Input 1 of 5 — the voter install. No other input moves; the electorate is the only thing that changes.
    @Test
    void trigger_voterInstall_arms() {
        AetherNode.wireQuorumCountTriggers(detector, fsm, this::voterSource);
        observeAllPeers();
        assertThat(detector.isArmed()).as("arming: peers observed, electorate empty").isFalse();

        installVoters();

        assertThat(detector.isArmed()).isTrue();
    }

    /// Input 2 of 5 — a member's reachability latch. A seeded MEMBER stays MEMBER on `SwimHealthy`, so no
    /// transition record exists for the change; only the FSM's latch edge announces it.
    @Test
    void trigger_reachabilityLatch_arms() {
        installVoters();
        // The voter source is inert here: the install is already past, and a replay would evaluate and hide a
        // broken latch trigger.
        AetherNode.wireQuorumCountTriggers(detector, fsm, _ -> {});
        assertThat(detector.currentMemberCount()).as("arming: electorate installed, only self observed").isEqualTo(1);
        assertThat(detector.isArmed()).isFalse();

        fsm.onSwimHealthy(B, 1L);
        assertThat(detector.isArmed()).as("self + B = 2 < 3: evaluated, not yet quorate").isFalse();
        fsm.onSwimHealthy(C, 1L);

        assertThat(detector.isArmed()).isTrue();
    }

    /// Input 3 of 5 — an exact-`Member` boundary crossing (SUSPECT → MEMBER refutation raising the strict count).
    @Test
    void trigger_memberBoundaryCrossing_arms() {
        observeAllPeers();
        List.of(B, C, D).forEach(peer -> fsm.onSwimSuspect(peer, 2L));
        installVoters();
        wireTransitionTrigger();
        assertThat(detector.currentMemberCount()).as("arming: self + E strict").isEqualTo(2);
        assertThat(detector.isArmed()).isFalse();

        fsm.onSwimHealthy(B, 3L);

        assertThat(detector.isArmed()).isTrue();
    }

    /// Input 4 of 5 — the presence sampler's stable-set edge (`nttReconcileTrigger` → `onNttReconcile`).
    @Test
    void trigger_samplerEdge_arms() {
        observeAllPeers();
        installVoters();
        assertThat(detector.isArmed()).as("arming: quorate inputs, nothing has evaluated").isFalse();

        AetherNode.onNttReconcile(detectorRef, new AtomicReference<>());

        assertThat(detector.isArmed()).isTrue();
    }

    /// Input 5 of 5 — the FSM's confirmed-death edge (`onMembershipDeath`).
    @Test
    void trigger_confirmedDeath_arms() {
        observeAllPeers();
        installVoters();
        assertThat(detector.isArmed()).as("arming: quorate inputs, nothing has evaluated").isFalse();

        AetherNode.onMembershipDeath(E, _ -> {}, detectorRef, new AtomicReference<>());

        assertThat(detector.isArmed()).isTrue();
    }

    /// The electorate narrows the count: a core the FSM counts as an observed MEMBER but that is not an installed
    /// voter (a staged, not-yet-admitted core) adds nothing, and an empty electorate yields 0 without a member walk.
    @Test
    void derivedMemberCount_countsOnlyInstalledVoters() {
        var staged = new NodeId("node-staged");
        var seeded = new java.util.HashSet<>(CORES);

        seeded.add(staged);
        fsm.seed(seeded);
        observeAllPeers();
        fsm.onSwimHealthy(staged, 1L);

        assertThat(AetherNode.derivedMemberCount(Option.some(fsm), SELF, Option.none())).isZero();

        installVoters();

        assertThat(fsm.strictCoreObservedMemberCount(SELF)).as("arming: the FSM sees six observed MEMBERs").isEqualTo(6);
        assertThat(AetherNode.derivedMemberCount(Option.some(fsm), SELF, voters.get())).isEqualTo(5);
        assertThat(AetherNode.derivedMemberCount(Option.none(), SELF, voters.get())).as("pre-FSM window").isZero();
    }

    /// The assembly really builds the detector from the derivation and really wires the two triggers; none of it
    /// is reachable from a unit test, so the call sites are pinned the way `NodeDepartureWiringTest` pins its own.
    @Test
    void assembly_derivesTheCountAndWiresTheTriggers() {
        var code = NodeDepartureWiringTest.assemblyCode();

        assertThat(code).contains("derivedMemberCount(Option.option(membershipFsmRef.get()),config.self(),clusterNode.voterConfiguration())");
        assertThat(code).contains("wireQuorumCountTriggers(quorumLossDetector,membershipFsm,clusterNode::onVoterConfiguration);");
    }

    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable);

            tasks.add(task);

            return task;
        }

        int pendingCount() {
            return (int) tasks.stream().filter(task -> !task.isDone()).count();
        }

        @Contract
        void fireAll() {
            List.copyOf(tasks).forEach(ManualTask::runIfLive);
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private boolean cancelled;
        private boolean done;

        ManualTask(Runnable runnable) {
            this.runnable = runnable;
        }

        @Contract
        void runIfLive() {
            if (cancelled || done) {
                return;
            }
            done = true;
            runnable.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
