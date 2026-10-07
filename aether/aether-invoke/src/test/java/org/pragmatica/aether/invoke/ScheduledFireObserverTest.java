// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1930 (owner rule: an operator-facing condition emits an event on its transition, and a recovery event): a fire that is
/// still in flight when the next tick arrives makes the task skip the tick. The operator is told ONCE per in-flight fire
/// however many ticks it skips (a per-tick line is a flood, and a 1 s task would write six hundred of them), and told again
/// when that fire resolves. A fire nobody was told about is not reported as resolved.
class ScheduledFireObserverTest {
    private record Held(long fireStartedAt, long inFlightMs) {}

    private record Released(long fireStartedAt, long inFlightMs, String outcome) {}

    private final CopyOnWriteArrayList<Held> held = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Released> released = new CopyOnWriteArrayList<>();
    private final ScheduledFireObserver observer = new ScheduledFireObserver() {
        @Override
        public Unit onFireHeld(ScheduledTaskKey task, long fireStartedAt, long inFlightMs) {
            held.add(new Held(fireStartedAt, inFlightMs));

            return Unit.unit();
        }

        @Override
        public Unit onFireReleased(ScheduledTaskKey task, long fireStartedAt, long inFlightMs, String outcome) {
            released.add(new Released(fireStartedAt, inFlightMs, outcome));

            return Unit.unit();
        }
    };
    private final ScheduledTaskRegistry registry = ScheduledTaskRegistry.scheduledTaskRegistry();
    private final CopyOnWriteArrayList<ScheduledTaskManagerTest.InvocationRecord> invocations = new CopyOnWriteArrayList<>();
    private final ScheduledTaskManagerTest.StubSliceInvoker stub = new ScheduledTaskManagerTest.StubSliceInvoker(invocations,
                                                                                                                 Option.none());
    private final NodeId self = new NodeId("node-self");
    private final Artifact artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
    private final MethodName method = MethodName.methodName("cleanup").unwrap();
    private ScheduledTaskManager manager;
    private ScheduledTaskManagerTest.TestLeaderManager leaders;

    private void start() {
        manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                            stub,
                                                            self,
                                                            _ -> {},
                                                            _ -> Option.none(),
                                                            new ScheduledTaskManagerTest.TestLeaderManager(self),
                                                            ScheduledTaskManager.DEFAULT_COMPLETION_BOUND,
                                                            observer);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache", artifact, method),
                                                                       ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.ALL)),
                                                   Option.none()));
        manager.onQuorumStateChange(ClusterStateNotification.active());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 8_000L;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /// One fire runs across several ticks: ONE held event, however many ticks it skipped; its resolution is reported once.
    @Test
    void oneEventPerInFlightFire_notPerTick_andARecoveryWhenItResolves() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        start();
        awaitTrue(() -> invocations.size() >= 1);
        // at least three more ticks arrive while the fire is still running
        Thread.sleep(3_300);

        assertThat(invocations).as("premise: only the one fire ran, every later tick was skipped").hasSize(1);
        assertThat(held).as("ONE held event for the fire, not one per skipped tick").hasSize(1);
        assertThat(held.getFirst().inFlightMs()).as("it says how long the fire had been in flight").isPositive();
        assertThat(released).as("not resolved yet").isEmpty();

        running.succeed(Unit.unit());
        awaitTrue(() -> !released.isEmpty());

        assertThat(released).as("the recovery, once").hasSize(1);
        assertThat(released.getFirst().outcome()).isEqualTo("executed");
        assertThat(released.getFirst().fireStartedAt()).as("of the same fire").isEqualTo(held.getFirst().fireStartedAt());
        manager.stop();
    }

    /// A failed fire and an unknown one are reported as such.
    @Test
    void recoveryNamesHowTheFireEnded_failed() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        start();
        awaitTrue(() -> !held.isEmpty());
        running.fail(Causes.cause("callee failed"));
        awaitTrue(() -> !released.isEmpty());
        manager.stop();

        assertThat(released).singleElement().extracting(Released::outcome).isEqualTo("failed");
    }

    @Test
    void recoveryNamesHowTheFireEnded_unknownAtTheBound() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        start();
        awaitTrue(() -> !held.isEmpty());
        running.fail(SliceInvokerError.CompletionUnknown.completionUnknown(artifact, method, Causes.cause("no response in time")));
        awaitTrue(() -> !released.isEmpty());
        manager.stop();

        assertThat(released).singleElement().extracting(Released::outcome).isEqualTo("unknown");
    }

    /// A fire that finishes before the next tick holds nothing, so there is nothing to announce and nothing to recover from.
    @Test
    void noEventsForAFireThatResolvesBeforeTheNextTick() throws Exception {
        start();
        awaitTrue(() -> invocations.size() >= 3);
        manager.stop();

        assertThat(invocations).hasSizeGreaterThanOrEqualTo(3);
        assertThat(held).isEmpty();
        assertThat(released).as("no recovery without a held event").isEmpty();
    }

    /// The next in-flight fire after a recovery is announced again: the rule is per fire.
    @Test
    void aSecondInFlightFire_isAnnouncedAgain() throws Exception {
        var first = Promise.<Unit> promise();

        stub.heldCompletion.set(first);
        start();
        awaitTrue(() -> !held.isEmpty());
        var second = Promise.<Unit> promise();

        stub.heldCompletion.set(second);
        first.succeed(Unit.unit());
        awaitTrue(() -> held.size() >= 2);
        manager.stop();

        assertThat(held).hasSize(2);
        assertThat(held.get(1).fireStartedAt()).as("another fire").isGreaterThan(held.getFirst().fireStartedAt());
    }

    /// The manual trigger holds the same claim: a tick skipped for it is announced too, and its release reads `completed`.
    @Test
    void aManualTriggerHoldingTheClaim_isAnnouncedAndReleasedAsCompleted() throws Exception {
        start();
        var key = ScheduledTaskKey.scheduledTaskKey("cache", artifact, method);

        assertThat(manager.tryClaim(key)).as("premise: the trigger took the claim").isTrue();
        awaitTrue(() -> !held.isEmpty());
        manager.release(key);
        manager.stop();

        assertThat(held).hasSize(1);
        assertThat(released).singleElement().extracting(Released::outcome).isEqualTo("completed");
    }

    private void startSingleModeAsLeader() {
        leaders = new ScheduledTaskManagerTest.TestLeaderManager(self);
        manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                            stub,
                                                            self,
                                                            _ -> {},
                                                            _ -> Option.none(),
                                                            leaders,
                                                            ScheduledTaskManager.DEFAULT_COMPLETION_BOUND,
                                                            observer);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache", artifact, method),
                                                                       ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.SINGLE)),
                                                   Option.none()));
        manager.onQuorumStateChange(ClusterStateNotification.active());
        leaders.setLeader(true);
        manager.onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.leaderChange(Option.some(self), true));
    }

    /// The owner's event rule: a hold nothing will ever resolve is a stale alarm. The leader loses leadership while the fire
    /// it announced as held is still running: that hold is released once, as `leadership-lost`, and the fire's own later
    /// completion adds nothing (its record is gone).
    @Test
    void leadershipLost_whileAHeldFireIsRunning_releasesItOnce_asLeadershipLost() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        startSingleModeAsLeader();
        awaitTrue(() -> !held.isEmpty());

        leaders.setLeader(false);
        manager.onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.leaderChange(Option.none(), false));

        assertThat(released).as("exactly one release for the held fire").hasSize(1);
        assertThat(released.getFirst().outcome()).isEqualTo("leadership-lost");
        assertThat(released.getFirst().fireStartedAt()).isEqualTo(held.getFirst().fireStartedAt());

        running.succeed(Unit.unit());
        Thread.sleep(300);
        manager.stop();

        assertThat(released).as("the fire's own completion does not release it a second time").hasSize(1);
    }

    /// No release for a fire that was never announced as held: the leader loses leadership before any tick was skipped.
    @Test
    void leadershipLost_beforeAnyTickWasSkipped_releasesNothing() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        startSingleModeAsLeader();
        awaitTrue(() -> invocations.size() >= 1);

        leaders.setLeader(false);
        manager.onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.leaderChange(Option.none(), false));
        running.succeed(Unit.unit());
        Thread.sleep(300);
        manager.stop();

        assertThat(held).as("premise: no tick had been skipped").isEmpty();
        assertThat(released).as("no release for a hold nobody was told about").isEmpty();
    }

    /// A scheduler that stops releases its announced holds too.
    @Test
    void schedulerStopped_whileAHeldFireIsRunning_releasesItAsLeadershipLost() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        startSingleModeAsLeader();
        awaitTrue(() -> !held.isEmpty());
        manager.stop();

        assertThat(released).singleElement().extracting(Released::outcome).isEqualTo("leadership-lost");
    }

    /// ALL-mode fires are not the leader's: losing leadership leaves them alone, but a scheduler that STOPS releases every
    /// announced hold, whatever the mode.
    @Test
    void allModeHeldFire_isLeftAloneByLeadershipLoss_butReleasedWhenTheSchedulerStops() throws Exception {
        var running = Promise.<Unit> promise();

        stub.heldCompletion.set(running);
        start();
        awaitTrue(() -> !held.isEmpty());
        manager.onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.leaderChange(Option.none(), false));

        assertThat(released).as("an ALL-mode fire does not depend on leadership").isEmpty();

        manager.stop();

        assertThat(released).singleElement().extracting(Released::outcome).isEqualTo("leadership-lost");
    }

    /// Losing leadership releases the LEADER's holds only: with an ALL-mode and a SINGLE-mode fire both held, only the
    /// SINGLE-mode one is released.
    @Test
    void leadershipLost_releasesOnlyTheSingleModeHold_notTheAllModeOne() throws Exception {
        var running = Promise.<Unit> promise();
        var refresh = MethodName.methodName("refresh").unwrap();

        stub.heldCompletion.set(running);
        startSingleModeAsLeader();
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache", artifact, refresh),
                                                                       ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.ALL)),
                                                   Option.none()));
        awaitTrue(() -> held.size() >= 2);

        assertThat(held).as("premise: both fires are held").hasSize(2);

        leaders.setLeader(false);
        manager.onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.leaderChange(Option.none(), false));

        assertThat(released).as("only the single-mode (leader's) hold is released").hasSize(1);
        manager.stop();
    }
}
