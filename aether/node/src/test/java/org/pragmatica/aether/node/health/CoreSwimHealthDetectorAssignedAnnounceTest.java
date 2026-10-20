// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1840: a worker holds its ANNOUNCE to the seeds until its activation directive is committed. Before
/// that it has no community, and the cores' membership scope admits no worker that is not a governor, so
/// every round of the announce loop was traffic they dropped. The assertions key on ANNOUNCE datagrams
/// addressed to the seed, which only the join announce loop sends (the first round fires after 500 ms).
class CoreSwimHealthDetectorAssignedAnnounceTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId SEED_ID = new NodeId("core-1");
    private static final InetSocketAddress SEED = new InetSocketAddress("127.0.0.9", 9101);
    /// Spans two announce rounds (500 ms apart) with margin.
    private static final long QUIET_MILLIS = 1_300;
    /// A bound no test reaches, for the tests that are not about the overdue warning.
    private static final TimeSpan NEVER_OVERDUE = timeSpan(10).minutes();
    private static final TimeSpan SHORT_BOUND = timeSpan(300).millis();

    private final List<SwimTransportCall> sent = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private NodeInfo self;
    private CoreSwimHealthDetector detector;
    private SwimProtocol swim;

    @BeforeEach
    void setUp() {
        self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var topology = new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self));

        detector = CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(),
                                                                 topology,
                                                                 Mockito.mock(Serializer.class),
                                                                 Mockito.mock(Deserializer.class));
        var ctx = detector.contextForTest();
        var transport = new RecordingTransport();

        detector.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        ctx.dispatch(new SwimHealthEvents.StartRequested());
        swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, transport, noopListener(), SELF, new InetSocketAddress("127.0.0.1", 9101))
                           .unwrap();
        ctx.dispatch(new SwimHealthEvents.ProtocolReady(swim, transport, GossipEncryptor.none()));
    }

    @AfterEach
    void tearDown() {
        swim.stop();
    }

    @Test
    void announceJoinWhenAssigned_beforeAssignment_announcesNothingButSeedsIdentity() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE);

        Thread.sleep(QUIET_MILLIS);

        assertThat(announcesToSeed()).as("no directive yet: nothing announced to the seed").isEmpty();
        assertThat(detector.selfIncarnation()).as("the identity is seeded all the same").isEqualTo(5L);
    }

    @Test
    void markAssigned_afterAHeldAnnounce_releasesItToTheSeeds() {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE);

        detector.markAssigned();

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(announcesToSeed()).as("released on assignment").isNotEmpty());
        assertThat(announcesToSeed().getFirst().message())
            .as("the held announcement is released unchanged")
            .isEqualTo(Announce.announce(self, "c", 5L, 7L));
    }

    @Test
    void announceJoinWhenAssigned_afterAssignment_announcesAtOnce() {
        detector.markAssigned();

        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(announcesToSeed()).as("already assigned: no hold").isNotEmpty());
    }

    @Test
    void markAssigned_repeated_releasesTheHeldAnnounceOnce() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE);
        detector.markAssigned();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !announcesToSeed().isEmpty());
        swim.onMessage(SEED, Ack.ack(SEED_ID, 0L, List.of()));
        Thread.sleep(200);

        var afterJoinAck = announcesToSeed().size();

        detector.markAssigned();
        Thread.sleep(QUIET_MILLIS);

        assertThat(announcesToSeed()).as("a repeated assignment must not re-arm the acknowledged join").hasSize(afterJoinAck);
    }

    @Test
    void announceJoinWhenAssigned_heldPastTheBound_raisesTheOverdueWarningOnce() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), SHORT_BOUND);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(codes()).as("still held after the bound").containsExactly(OperatorWarningCode.WORKER_ASSIGNMENT_OVERDUE));
        assertThat(warnings.getFirst().subject()).as("names the node").isEqualTo(SELF.id());
        assertThat(warnings.getFirst().message()).as("names the node and the recovery").contains(SELF.id()).contains("replace the node");
        Thread.sleep(QUIET_MILLIS);

        assertThat(codes()).as("one warning per hold").hasSize(1);
        assertThat(announcesToSeed()).as("the hold is kept: no fallback announce").isEmpty();
    }

    @Test
    void announceJoinWhenAssigned_insideTheBound_raisesNothingUntilTheBoundPasses() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), timeSpan(900).millis());

        Thread.sleep(300);

        assertThat(warnings).as("held inside the bound: nothing raised yet").isEmpty();
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(codes()).as("raised once the bound has passed")
                                                       .containsExactly(OperatorWarningCode.WORKER_ASSIGNMENT_OVERDUE));
    }

    @Test
    void markAssigned_afterTheOverdueWarning_raisesTheRecovery() {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), SHORT_BOUND);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !warnings.isEmpty());

        detector.markAssigned();

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(codes()).containsExactly(OperatorWarningCode.WORKER_ASSIGNMENT_OVERDUE,
                                                                        OperatorWarningCode.WORKER_ASSIGNED));
    }

    @Test
    void markAssigned_beforeTheBound_raisesNothingAndCancelsTheWarning() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), timeSpan(600).millis());

        detector.markAssigned();
        Thread.sleep(QUIET_MILLIS);

        assertThat(warnings).as("released inside the bound: no warning, no recovery").isEmpty();
    }

    @Test
    void stop_whileHeld_cancelsTheOverdueWarning() throws InterruptedException {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), timeSpan(600).millis());

        detector.stop();
        Thread.sleep(QUIET_MILLIS);

        assertThat(warnings).as("a stopped node raises no overdue warning").isEmpty();
    }

    /// The directive commits while the hold is being set: the holder has decided to hold but has not stored the
    /// call. The assignment must wait for the hold and then release it, not slip between decision and store.
    @Test
    void markAssigned_whileTheHoldIsBeingSet_releasesTheHeldAnnounce() throws InterruptedException {
        var parked = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);

        detector.afterHoldDecisionForTest(() -> park(parked, proceed));
        var holder = new Thread(() -> detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE));

        holder.start();
        assertThat(parked.await(5, TimeUnit.SECONDS)).as("holder parked mid-hold").isTrue();
        var assigner = new Thread(detector::markAssigned);

        assigner.start();
        Thread.sleep(200);
        proceed.countDown();
        holder.join(5_000);
        assigner.join(5_000);

        await().atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(announcesToSeed()).as("the release won the race").isNotEmpty());
    }

    @Test
    void markAssigned_waitsForTheAssignmentGate() throws Exception {
        detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE);
        var pool = Executors.newSingleThreadExecutor();

        try {
            var release = new AtomicReference<Future<?>>();
            var doneWhileGated = new AtomicBoolean(true);

            detector.underAssignmentGateForTest(() -> doneWhileGated.set(submitAndCheck(pool, detector::markAssigned, release)));

            assertThat(doneWhileGated).as("markAssigned must wait for the gate").isFalse();
            release.get().get(5, TimeUnit.SECONDS);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !announcesToSeed().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void announceJoinWhenAssigned_waitsForTheAssignmentGate() throws Exception {
        var pool = Executors.newSingleThreadExecutor();

        try {
            var call = new AtomicReference<Future<?>>();
            var doneWhileGated = new AtomicBoolean(true);

            detector.underAssignmentGateForTest(() -> doneWhileGated.set(submitAndCheck(pool,
                                                                                       () -> detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE),
                                                                                       call)));

            assertThat(doneWhileGated).as("announceJoinWhenAssigned must wait for the gate").isFalse();
            call.get().get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /// Release wins: the assignment lands while the hold is waiting at the gate. The hold must read the assignment
    /// after it enters the gate; a hold that read `assigned` before waiting would store the call and be held forever.
    @Test
    void markAssigned_landingWhileTheHoldWaitsAtTheGate_endsAnnounced() throws Exception {
        var pool = Executors.newSingleThreadExecutor();

        try {
            var call = new AtomicReference<Future<?>>();

            detector.underAssignmentGateForTest(() -> {
                submitAndCheck(pool, () -> detector.announceJoinWhenAssigned(self, "c", 5L, 7L, List.of(SEED), NEVER_OVERDUE), call);
                detector.markAssigned();
            });
            call.get().get(5, TimeUnit.SECONDS);

            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(announcesToSeed()).as("release won: announced, not held forever").isNotEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    /// Submits `task`, gives it time to run if it could, and reports whether it finished while the gate was held.
    private static boolean submitAndCheck(ExecutorService pool, Runnable task, AtomicReference<Future<?>> submitted) {
        submitted.set(pool.submit(task));
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return submitted.get().isDone();
    }

    private static void park(CountDownLatch parked, CountDownLatch proceed) {
        parked.countDown();
        try {
            proceed.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private List<SwimTransportCall> announcesToSeed() {
        return sent.stream()
                   .filter(call -> call.target().equals(SEED) && call.message() instanceof Announce)
                   .toList();
    }

    private static SwimMembershipListener noopListener() {
        return new SwimMembershipListener() {
            @Override public void onMemberJoined(SwimMember member) {}
            @Override public void onMemberSuspect(SwimMember member) {}
            @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
            @Override public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private record SwimTransportCall(InetSocketAddress target, SwimMessage message) {}

    private final class RecordingTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sent.add(new SwimTransportCall(target, message));

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }
}
