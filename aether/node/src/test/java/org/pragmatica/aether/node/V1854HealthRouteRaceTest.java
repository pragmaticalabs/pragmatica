// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;

/// v1854: the run-9 ordering driven through the HEALTH ROUTE's own derivation (`StatusRoutes.quorumStatus`, the
/// source of `/api/v1/health` `quorum`), not only the detector snapshot, then a later quorum loss to the fence.
class V1854HealthRouteRaceTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final List<NodeId> CORES = List.of(SELF,
                                                      new NodeId("node-b"),
                                                      new NodeId("node-c"),
                                                      new NodeId("node-d"),
                                                      new NodeId("node-e"));

    private final AtomicReference<Option<VoterConfiguration>> voters = new AtomicReference<>(Option.none());
    private final List<Consumer<VoterConfiguration>> voterListeners = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();
    private final List<QuorumLossIntent> intents = new ArrayList<>();
    private final MembershipFsm fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                                   System::currentTimeMillis,
                                                                   Long.MAX_VALUE,
                                                                   TimeSpan.timeSpan(40).millis());
    private final AtomicReference<MembershipFsm> fsmRef = new AtomicReference<>(fsm);
    private final QuorumLossDetector detector = QuorumLossDetector.quorumLossDetector(membershipConfig(),
                                                                                       () -> voters.get().map(v -> v.members().size()).or(0),
                                                                                       () -> AetherNode.derivedMemberCount(Option.option(fsmRef.get()),
                                                                                                                           SELF,
                                                                                                                           voters.get()),
                                                                                       TimeSource.system(),
                                                                                       this::schedule);
    private final AtomicReference<QuorumLossDetector> detectorRef = new AtomicReference<>(detector);

    @Test
    void samplerEdgeBeforeVoterInstall_healthRouteQuorumTrue_thenLossFences() throws Exception {
        detector.setQuorumLossListener(intents::add);
        fsm.seed(Set.copyOf(CORES));
        var journal = TransitionJournal.transitionJournal();
        fsm.onTransition(record -> AetherNode.onFsmTransition(journal, detectorRef, record));
        AetherNode.wireQuorumCountTriggers(detector, fsm, this::voterSource);

        CORES.subList(1, 5).forEach(peer -> fsm.onSwimHealthy(peer, 1L));
        AetherNode.onNttReconcile(detectorRef, new AtomicReference<>());
        assertThat(detector.isArmed()).as("pre-install: empty electorate").isFalse();

        var configuration = VoterConfiguration.voterConfiguration(0, CORES).unwrap();
        voters.set(Option.some(configuration));
        voterListeners.forEach(listener -> listener.accept(configuration));

        var health = quorumStatus();
        assertThat(heldOf(health)).as("/api/v1/health quorum after install").isTrue();
        assertThat(health.toString()).contains("observedMembers=5").contains("requiredThreshold=3");
        assertThat(detector.isArmed()).isTrue();

        CORES.subList(1, 4).forEach(peer -> fsm.onSwimFaulty(peer, 2L));

        assertThat(heldOf(quorumStatus())).as("/api/v1/health quorum after losing 3 of 5").isFalse();
        assertThat(detector.isBelowThreshold()).isTrue();

        List.copyOf(tasks).forEach(Task::runIfLive);
        assertThat(intents).as("the self-fence intent").hasSize(1);
    }

    private Object quorumStatus() throws Exception {
        var node = (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                           new Class[]{ManageableNode.class},
                                                           (_, method, _) -> switch (method.getName()) {
                                                               case "quorumLossSnapshot" -> Option.some(QuorumLossSnapshot.from(detector));
                                                               default -> throw new UnsupportedOperationException(method.getName());
                                                           });
        var routes = Class.forName("org.pragmatica.aether.api.routes.StatusRoutes");
        Method quorumStatus = routes.getDeclaredMethod("quorumStatus", ManageableNode.class);
        quorumStatus.setAccessible(true);
        return quorumStatus.invoke(null, node);
    }

    private static boolean heldOf(Object status) throws Exception {
        var held = status.getClass().getDeclaredMethod("held");
        held.setAccessible(true);
        return (boolean) held.invoke(status);
    }

    private void voterSource(Consumer<VoterConfiguration> listener) {
        voterListeners.add(listener);
        voters.get().onPresent(listener);
    }

    private ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
        var task = new Task(runnable);
        tasks.add(task);
        return task;
    }

    private static final class Task implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private boolean cancelled;
        private boolean done;

        Task(Runnable runnable) {
            this.runnable = runnable;
        }

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
