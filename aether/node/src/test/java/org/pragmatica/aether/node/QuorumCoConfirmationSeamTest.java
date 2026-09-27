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

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.SwimHealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;

/// #1560 wiring pin for `AetherNode.buildQuorumCoConfirmation`, the snapshot the quorum-loss self-fence
/// consults before it drains. #1390 made its counted set the installed voter set, which is health-blind:
/// every peer the FSM had already demoted (DEPARTING / DEAD) came back as a "stuck" member, and an isolated
/// core, whose SWIM still read those peers SUSPECTED, suppressed its own fence with an effective quorum of
/// 5 of 5. The counted set is the FSM's counted CORE members narrowed to the voters.
class QuorumCoConfirmationSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final NodeId PEER_D = new NodeId("node-d");
    private static final NodeId PEER_E = new NodeId("node-e");
    private static final Set<NodeId> VOTERS = Set.of(SELF, PEER_B, PEER_C, PEER_D, PEER_E);
    private static final int THRESHOLD = 3;

    /// The isolated-core shape at the firing instant: three peers already demoted by the FSM, one still
    /// SUSPECT, and SWIM reporting every peer SUSPECTED (Lifeguard stretches suspicion on a node whose
    /// probes all fail). Effective quorum must be self + the one SUSPECT peer = 2, below threshold.
    @Test
    void buildQuorumCoConfirmation_fsmDemotedPeersSwimSuspected_notCountedAsStuck() {
        var fsm = isolatedCoreFsm();

        assertThat(fsm.coreCountedMembers()).as("arming: the FSM has demoted B, C and D out of its counted set,"
                                                + " while the voter set still names all five")
                                            .containsExactlyInAnyOrder(SELF, PEER_E);

        var snapshot = AetherNode.buildQuorumCoConfirmation(fsm, _ -> SwimHealth.SUSPECTED, VOTERS);

        assertThat(snapshot.countedCount()).isEqualTo(2);
        assertThat(snapshot.swimAliveStuckMembers()).containsExactly(PEER_E);
        assertThat(snapshot.effectiveQuorumCount()).isEqualTo(2);
        assertThat(snapshot.suppresses(THRESHOLD)).as("an isolated core must not suppress its own fence").isFalse();
    }

    /// The voter narrowing still holds: a counted core that is not an installed voter never lifts the
    /// effective count.
    @Test
    void buildQuorumCoConfirmation_countedCoreOutsideVoters_notCounted() {
        var fsm = isolatedCoreFsm();

        var snapshot = AetherNode.buildQuorumCoConfirmation(fsm, _ -> SwimHealth.HEALTHY, Set.of(SELF, PEER_B, PEER_C));

        assertThat(snapshot.countedCount()).isEqualTo(1);
        assertThat(snapshot.swimAliveStuckMembers()).isEmpty();
    }

    /// #1560 race, deterministically: the isolated core's `T` check lands BEFORE the FSM's down-hysteresis
    /// demotions (SUSPECT → DEPARTING) are applied, so the gate reads every peer as a SWIM-alive stuck member
    /// and suppresses. The demotions land afterwards. Without the suppressed check's re-arm the fence is
    /// stranded there (the rc4 log: one SUPPRESSED line, no second check); with it the next re-check fences.
    @Test
    void isolatedCore_firstCheckBeforeFsmDemotions_reCheckFencesOnceDemotionsLand() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              Long.MAX_VALUE,
                                              TimeSpan.timeSpan(40).millis());
        var scheduler = new ManualScheduler();
        var intents = new ArrayList<QuorumLossIntent>();
        var detector = QuorumLossDetector.quorumLossDetector(membershipConfig(),
                                                             VOTERS::size,
                                                             TimeSource.system(),
                                                             scheduler);

        detector.setQuorumLossListener(intents::add);
        detector.setCoConfirmationSupplier(() -> AetherNode.buildQuorumCoConfirmation(fsm,
                                                                                      _ -> SwimHealth.SUSPECTED,
                                                                                      VOTERS));
        fsm.seed(VOTERS);
        detector.onMemberCountChanged(strictVoterCount(fsm));
        assertThat(detector.isArmed()).as("arming: a formed five-core cluster").isTrue();

        List.of(PEER_B, PEER_C, PEER_D, PEER_E).forEach(peer -> fsm.onSwimSuspect(peer, 1L));
        detector.onMemberCountChanged(strictVoterCount(fsm));
        scheduler.fireAll();

        assertThat(intents).as("at T every peer is still FSM-SUSPECT and SWIM-SUSPECTED: suppressed").isEmpty();

        List.of(PEER_B, PEER_C, PEER_D).forEach(fsm::onDownHysteresisMet);
        assertThat(fsm.coreCountedMembers()).as("arming: the demotions landed after the first check")
                                            .containsExactlyInAnyOrder(SELF, PEER_E);
        scheduler.fireAll();

        assertThat(intents).as("the re-check fences once the FSM demotions land").hasSize(1);
    }

    private static int strictVoterCount(MembershipFsm fsm) {
        return (int) fsm.strictCoreMembers()
                        .stream()
                        .filter(VOTERS::contains)
                        .count();
    }

    private static MembershipFsm isolatedCoreFsm() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              Long.MAX_VALUE,
                                              TimeSpan.timeSpan(40).millis());

        fsm.seed(VOTERS);
        fsm.onDrainRequested(PEER_B);
        fsm.onDrainRequested(PEER_C);
        fsm.onDrainRequested(PEER_D);
        fsm.onSwimSuspect(PEER_E, 1L);

        return fsm;
    }

    /// Captures scheduled checks; tests run them explicitly. `fireAll` runs a snapshot, so a check that
    /// re-arms schedules its successor for the NEXT `fireAll`.
    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable);

            tasks.add(task);

            return task;
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
