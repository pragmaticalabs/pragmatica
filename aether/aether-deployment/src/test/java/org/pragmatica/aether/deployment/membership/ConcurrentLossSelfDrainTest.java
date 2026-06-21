// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector.quorumLossDetector;

/// Integration test for the concurrent-loss self-drain wiring: a real [`MembershipFsm`] is wired to
/// a real [`QuorumLossDetector`] EXACTLY as `AetherNode` does — `fsm.onTransition` → on a transition
/// crossing the exact-`Member` boundary → `detector.onMemberCountChanged(fsm.strictCoreMemberCount())`.
///
/// It proves the fix for the 3-of-5 concurrent-loss wedge: when three core members enter SUSPECT at
/// once, the strict core count drops to 2 immediately, the Member→Suspect transition edges re-feed
/// the detector promptly (NOT only on the 15s presence down-hysteresis / DEAD edge), and the
/// quorum-loss drain window arms after `splitTimeout`. A SUSPECT→MEMBER refutation before the window
/// elapses cancels the pending drain, and a minority loss that never crosses below threshold never
/// arms.
///
/// The detector is primed to ARMED by feeding the quorate strict count (5 >= threshold 3) once,
/// exactly as the production count path reaches quorum before any loss. No co-confirmation supplier
/// is wired, so the gate defaults to `absent` (never suppresses) and the strict shortfall fires.
class ConcurrentLossSelfDrainTest {
    private static final NodeId M0 = new NodeId("core-0");
    private static final NodeId M1 = new NodeId("core-1");
    private static final NodeId M2 = new NodeId("core-2");
    private static final NodeId M3 = new NodeId("core-3");
    private static final NodeId M4 = new NodeId("core-4");
    private static final Set<NodeId> FIVE_CORES = Set.of(M0, M1, M2, M3, M4);

    /// The configured core-cluster size (the quorum denominator) — static topology config, not the
    /// live FSM count. coreCount=5 → required threshold = 5/2 + 1 = 3.
    private static final int CONFIGURED_CORE_COUNT = 5;

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MembershipFsm fsm;
    private QuorumLossDetector detector;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        fsm = MembershipFsm.membershipFsm();
        detector = quorumLossDetector(membershipConfig(), () -> CONFIGURED_CORE_COUNT, timeSource, scheduler);
        detector.setQuorumLossListener(listener);

        // Bring the FSM to the steady five-MEMBER state BEFORE wiring the detector — the seed promotes
        // straight to MEMBER, and we model the settled cluster (post-formation) the way production feeds
        // the detector from the settled count, not from each boot promotion. Strict core count = 5.
        fsm.seed(FIVE_CORES);
        assertThat(fsm.strictCoreMemberCount()).isEqualTo(CONFIGURED_CORE_COUNT);

        // Wire the FSM to the detector EXACTLY as AetherNode does: re-feed the strict core count to
        // the detector on every transition crossing the exact-`Member` boundary.
        fsm.onTransition(this::onFsmTransition);

        // Prime the detector to ARMED with the settled quorate strict count (5 >= threshold 3) — the
        // cold-start latch the production count path crosses before any loss. No window is open and no
        // firing task is scheduled in this steady state.
        detector.onMemberCountChanged(fsm.strictCoreMemberCount());
        assertThat(detector.isArmed()).isTrue();
        assertThat(detector.isBelowThreshold()).isFalse();
        assertThat(scheduler.pendingTasks()).isEmpty();
    }

    /// The AetherNode wiring under test: re-feed the strict core count to the detector whenever a
    /// transition crosses the exact-`Member` boundary (entering OR leaving MEMBER).
    @Contract
    private void onFsmTransition(MembershipTransitionRecord record) {
        if (crossesMemberBoundary(record)) {
            detector.onMemberCountChanged(fsm.strictCoreMemberCount());
        }
    }

    /// Byte-identical to `AetherNode#crossesMemberBoundary`.
    private static boolean crossesMemberBoundary(MembershipTransitionRecord record) {
        return "Member".equals(record.fromState()) != "Member".equals(record.toState());
    }

    @Test
    void concurrentLossOfThreeOfFive_suspectEdges_armsDrainAfterSplitTimeout() {
        // Three of five cores enter SUSPECT at once (incarnation strictly above the seed's). Each
        // Member→Suspect transition fires the wiring; after the third, strict count = 2 < threshold 3.
        fsm.onSwimSuspect(M0, 2L);
        fsm.onSwimSuspect(M1, 2L);
        fsm.onSwimSuspect(M2, 2L);

        assertThat(fsm.strictCoreMemberCount()).isEqualTo(2);
        assertThat(detector.currentMemberCount()).isEqualTo(2);
        assertThat(detector.isBelowThreshold()).isTrue();
        assertThat(detector.currentRequiredThreshold()).isEqualTo(3);

        // The detector armed exactly one firing task with the full split-timeout delay.
        assertThat(scheduler.pendingTasks()).hasSize(1);
        assertThat(scheduler.pendingTasks().getFirst().delay()).isEqualTo(membershipConfig().splitTimeout());

        timeSource.advanceTimeMillis(membershipConfig().splitTimeout().millis());
        scheduler.fireAll();

        assertThat(listener.events()).hasSize(1);
        assertThat(listener.events().getFirst().observedLocalQuorumCount()).isEqualTo(2);
        assertThat(listener.events().getFirst().requiredThreshold()).isEqualTo(3);
    }

    @Test
    void concurrentLossThenRefutation_recoveryBeforeWindow_cancelsDrain() {
        // Three SUSPECT → window armed (strict count 2 < 3).
        fsm.onSwimSuspect(M0, 2L);
        fsm.onSwimSuspect(M1, 2L);
        fsm.onSwimSuspect(M2, 2L);
        assertThat(detector.isBelowThreshold()).isTrue();
        assertThat(scheduler.pendingTasks()).hasSize(1);

        // One of the three recovers (refutation back to MEMBER, higher incarnation) BEFORE firing →
        // strict count back to 3 >= threshold, cancelling the pending drain.
        fsm.onSwimHealthy(M0, 3L);
        assertThat(fsm.strictCoreMemberCount()).isEqualTo(3);
        assertThat(detector.isBelowThreshold()).isFalse();
        assertThat(scheduler.pendingTasks().getFirst().cancelled()).isTrue();

        timeSource.advanceTimeMillis(membershipConfig().splitTimeout().millis());
        scheduler.fireAll();

        assertThat(listener.events()).as("refutation before the window cancels the self-drain").isEmpty();
    }

    @Test
    void minorityLoss_twoOfFiveSuspect_neverArms() {
        // Only two of five cores enter SUSPECT → strict count = 3 >= threshold 3 (still quorate).
        fsm.onSwimSuspect(M0, 2L);
        fsm.onSwimSuspect(M1, 2L);

        assertThat(fsm.strictCoreMemberCount()).isEqualTo(3);
        assertThat(detector.currentMemberCount()).isEqualTo(3);
        assertThat(detector.isBelowThreshold()).isFalse();
        assertThat(scheduler.pendingTasks()).as("a minority loss never crosses below threshold, so no window opens").isEmpty();

        timeSource.advanceTimeMillis(membershipConfig().splitTimeout().millis());
        scheduler.fireAll();

        assertThat(listener.events()).as("minority loss never self-drains").isEmpty();
    }

    private static final class RecordingListener implements java.util.function.Consumer<QuorumLossIntent> {
        private final List<QuorumLossIntent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(QuorumLossIntent intent) {
            events.add(intent);
        }

        List<QuorumLossIntent> events() {
            return List.copyOf(events);
        }
    }

    /// Controllable time source — advances only on explicit method calls.
    private static final class TestTimeSource implements TimeSource {
        private volatile long nanos = 0L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Contract
        void advanceTimeMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    /// Manual scheduler — captures `(Runnable, delay)` pairs without ever invoking them on a
    /// background thread. Tests drive fire/cancel explicitly via `fireAll()` / the future's cancel.
    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable, delay);

            tasks.add(task);

            return task;
        }

        @Contract
        synchronized void fireAll() {
            for (var task : List.copyOf(tasks)) {
                task.runIfLive();
            }
        }

        synchronized List<ManualTask> pendingTasks() {
            return List.copyOf(tasks);
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private final TimeSpan delay;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualTask(Runnable runnable, TimeSpan delay) {
            this.runnable = runnable;
            this.delay = delay;
        }

        TimeSpan delay() {
            return delay;
        }

        boolean cancelled() {
            return cancelled;
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
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay.nanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled || done;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
