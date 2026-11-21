/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.AppGossip;
import org.pragmatica.swim.SwimMessage.IdentityRefused;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimMessage.WhoAmI;
import org.pragmatica.swim.SwimMessage.WhoAmIReply;
import org.pragmatica.swim.SwimSelfIsolationLatchAtomicityTest.SilentListener;
import org.pragmatica.swim.SwimSelfIsolationLatchAtomicityTest.SilentTransport;
import org.pragmatica.swim.TransportObservation.HintOrigin;
import org.pragmatica.swim.TransportObservation.IdentityRefuted;
import org.pragmatica.swim.TransportObservation.PeerReachable;
import org.pragmatica.swim.TransportObservation.PeerResponsive;
import org.pragmatica.swim.TransportObservation.PeerUnreachable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #3529: every mutation of SWIM member, suspicion and sibling state runs on the protocol's one serial executor.
/// The protocol counts each write that runs anywhere else (its confinement sensor); this test drives every entry
/// point, from several threads while the tick and the probe-timeout callbacks run, and requires the count to stay 0.
class SwimStateConfinementTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9300);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9301);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9302);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9303);
    private static final NodeInfo SELF_INFO = NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9000));
    private static final NodeInfo INFO_C = NodeInfo.nodeInfo(NODE_C, new NodeAddress("127.0.0.1", 9003));
    private static final long TOKEN = 0x1111L;
    private static final long OTHER_TOKEN = 0x2222L;
    private static final int THREADS = 4;
    private static final long WAIT_MS = 10_000;
    private static final Map<String, String> LABELS = Map.of("role", "core");

    @Test
    void everyEntryPoint_mutatesOnlyOnTheSerialExecutor() throws InterruptedException {
        var tokens = BootTokens.bootTokens(0x5E1FL);
        var faulty = ConcurrentHashMap.<NodeId>newKeySet();
        var protocol = protocol(new FaultyRecordingListener(faulty));

        protocol.setBootTokens(tokens);
        protocol.setMembershipEligibility(_ -> true);
        protocol.reportSelfQuorum(QuorumReport.LOST);
        protocol.announceJoin(SELF_INFO, "cluster", 1L, 0x5E1FL, List.of(ADDR_B));
        protocol.announceJoin(SELF_INFO, "cluster", 1L, List.of());
        protocol.addSeedMember(NODE_A, ADDR_A);
        protocol.addSeedMember(NODE_B, ADDR_B);
        assertThat(protocol.start().isSuccess()).as("control: the tick runs").isTrue();

        runConcurrently(protocol, tokens);
        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(faulty).as("control: the tick and the probe timeouts drove B to FAULTY")
                                                      .contains(NODE_B));
        exerciseTestSeams(protocol);
        protocol.stop();

        assertThat(protocol.offExecutorMutationsForTest()).as("member/suspicion writes off the serial executor")
                                                          .isZero();
    }

    /// The executor's run-to-completion: a task submitted from inside a task runs after it, not nested in it.
    @Test
    void submissionFromInsideATask_runsAfterTheCurrentTask() {
        var protocol = protocol(new SilentListener());
        var seenInside = new AtomicBoolean(true);

        protocol.onStateExecutorForTest(() -> nestedSubmission(protocol, seenInside));

        assertThat(seenInside.get()).as("the nested submission had not run while the outer task was running").isFalse();
        assertThat(protocol.members().containsKey(NODE_A)).as("it ran after the outer task, before the call returned")
                                                          .isTrue();
        assertThat(protocol.offExecutorMutationsForTest()).isZero();
    }

    /// A submission never waits for another drainer: while another thread holds the executor, the entry point
    /// returns at once with its write queued, and the holder's drain applies it after the held task, serially.
    @Test
    void contendedSubmission_returnsAtOnceAndIsAppliedSeriallyAfterTheHolder() throws InterruptedException {
        var protocol = protocol(new SilentListener());
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> protocol.onStateExecutorForTest(() -> hold(held, release)), "confinement-holder");

        holder.start();
        assertThat(held.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the holder owns the executor").isTrue();
        protocol.addSeedMember(NODE_A, ADDR_A);
        assertThat(protocol.members().containsKey(NODE_A)).as("returned while queued behind the holder, not applied yet")
                                                          .isFalse();
        release.countDown();
        holder.join(WAIT_MS);

        assertThat(protocol.members().containsKey(NODE_A)).as("applied once the holder released the executor").isTrue();
        assertThat(protocol.offExecutorMutationsForTest()).isZero();
    }

    /// #3560, the window: a bare `onMessage` while another thread holds the executor (the probe tick draining, here
    /// held on purpose) returns with the message still queued. This is the production contract, and exactly what a
    /// test must not assert across: the guard (`SwimDeliveryGuardTest`) refuses a bare `onMessage` in a test.
    @Test
    void bareOnMessage_whileTheExecutorIsHeld_returnsBeforeTheMessageIsApplied() throws InterruptedException {
        var protocol = protocol(new SilentListener());
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> protocol.onStateExecutorForTest(() -> hold(held, release)), "delivery-holder");

        holder.start();
        assertThat(held.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the holder owns the executor").isTrue();
        // ASYNC-DELIVERY: pins the production contract, a bare onMessage on a held executor returns before the message is applied
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(alive(NODE_A, 0))));
        assertThat(protocol.members().containsKey(NODE_A)).as("returned while queued behind the holder, not applied yet")
                                                          .isFalse();
        release.countDown();
        holder.join(WAIT_MS);

        assertThat(protocol.members().containsKey(NODE_A)).as("applied by the holder's drain after it released").isTrue();
    }

    /// #3560, the seam: the same scenario through `deliverForTest` does not return until the message is applied,
    /// so an assertion on the next line is safe. The delivering thread is seen waiting on the held executor (a
    /// seam that only enqueued would let it finish at once), and after it returns the message's effect is visible.
    @Test
    void deliverForTest_whileTheExecutorIsHeld_waitsAndReturnsOnlyOnceTheMessageIsApplied() throws InterruptedException {
        var protocol = protocol(new SilentListener());
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(() -> protocol.onStateExecutorForTest(() -> hold(held, release)), "delivery-holder");
        var deliverer = new Thread(() -> protocol.deliverForTest(ADDR_B, new Ping(NODE_B, 1L, List.of(alive(NODE_A, 0)))),
                                   "delivery-deliverer");

        holder.start();
        assertThat(held.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the holder owns the executor").isTrue();
        deliverer.start();
        await().atMost(Duration.ofSeconds(10))
               .until(() -> deliverer.getState() == Thread.State.TIMED_WAITING || !deliverer.isAlive());

        assertThat(deliverer.isAlive()).as("the seam waits for the held executor instead of returning").isTrue();
        assertThat(protocol.members().containsKey(NODE_A)).as("not applied while the holder runs").isFalse();
        release.countDown();
        holder.join(WAIT_MS);
        deliverer.join(WAIT_MS);

        assertThat(deliverer.isAlive()).as("the seam returned after the holder released").isFalse();
        assertThat(protocol.members().containsKey(NODE_A)).as("applied by the time the seam returned").isTrue();
        assertThat(protocol.offExecutorMutationsForTest()).isZero();
    }

    /// The sensor is live: a write reached off the executor is counted. Drives the gossip chain handleMessage ->
    /// handlePing -> processPiggyback -> applyUpdate -> applyExistingMember -> adoptGossipedLabels off the executor;
    /// the label adoption's member write (storeScopedMember) is the only write the labelled ping adds over the
    /// unlabelled control ping, so it must add to the count.
    @Test
    void sensor_gossipedLabelAdoptionOffTheExecutor_isCounted() {
        var protocol = protocol(new SilentListener());

        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        protocol.handleMessageOffExecutorForTest(ADDR_A, new Ping(NODE_A, 1L, List.of(alive(NODE_A, 0))));
        var unlabelledCount = protocol.offExecutorMutationsForTest();

        protocol.handleMessageOffExecutorForTest(ADDR_A, new Ping(NODE_A, 2L, List.of(labelled(NODE_A))));
        var labelledCount = protocol.offExecutorMutationsForTest() - unlabelledCount;

        assertThat(protocol.members().get(NODE_A).labels()).as("control: the chain reached the label adoption")
                                                           .isEqualTo(LABELS);
        assertThat(unlabelledCount).as("the sensor counts a write off the executor").isPositive();
        assertThat(labelledCount).as("the label adoption's member write off the executor is counted")
                                 .isGreaterThan(unlabelledCount);
    }

    private static void nestedSubmission(SwimProtocol protocol, AtomicBoolean seenInside) {
        protocol.addSeedMember(NODE_A, ADDR_A);
        seenInside.set(protocol.members().containsKey(NODE_A));
    }

    private static void hold(CountDownLatch held, CountDownLatch release) {
        held.countDown();
        try {
            release.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runConcurrently(SwimProtocol protocol, BootTokens tokens) throws InterruptedException {
        var go = new AtomicBoolean(false);
        var done = new CountDownLatch(THREADS);

        for (var t = 0; t < THREADS; t++) {
            var index = t;

            new Thread(() -> driveAfter(go, done, () -> driveEntryPoints(protocol, tokens, index)), "confinement-" + t).start();
        }

        go.set(true);
        assertThat(done.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("every driver finished").isTrue();
    }

    private static void driveAfter(AtomicBoolean go, CountDownLatch done, Runnable action) {
        while (!go.get()) {
            Thread.onSpinWait();
        }

        try {
            action.run();
        } finally {
            done.countDown();
        }
    }

    /// Every public and callback entry point that writes state: messages of each kind, transport hints of each
    /// kind, scope changes, seeding, the self-quorum report, the join announce and the retirement callback.
    private static void driveEntryPoints(SwimProtocol protocol, BootTokens tokens, int index) {
        for (var round = 0; round < 50; round++) {
            var seq = 1_000L * index + round;
            var evenRound = round % 2 == 0;

            protocol.deliverForTest(ADDR_A, new Ping(NODE_A, seq, List.of(alive(NODE_A, round))));
            protocol.deliverForTest(ADDR_C, new Ping(NODE_C, seq, List.of(update(NODE_B, MemberState.SUSPECT, round))));
            protocol.deliverForTest(ADDR_A, new Ack(NODE_A, seq, List.of(alive(NODE_C, round))));
            protocol.deliverForTest(ADDR_A, new PingReq(NODE_A, NODE_C, seq));
            protocol.deliverForTest(ADDR_C, Announce.announce(INFO_C, "cluster", round, TOKEN));
            protocol.deliverForTest(ADDR_A, new WhoAmI(NODE_A));
            protocol.deliverForTest(ADDR_A, new WhoAmIReply(SELF_ADDR));
            protocol.deliverForTest(ADDR_C, new IdentityRefused(NODE_C, SELF_ID, "test"));
            protocol.deliverForTest(ADDR_A, AppGossip.appGossip(NODE_A, 7, new byte[]{1}));
            protocol.recordTransportHint(NODE_A, new PeerReachable(NODE_A));
            protocol.recordTransportHint(NODE_A, new PeerResponsive(NODE_A));
            protocol.recordTransportHint(NODE_C,
                                         new PeerUnreachable(NODE_C, Causes.cause("test"), HintOrigin.PEER_UNRESPONSIVE));
            protocol.recordTransportHint(NODE_C, new PeerUnreachable(NODE_C, Causes.cause("test"), HintOrigin.LINK_LOST));
            protocol.recordTransportHint(NODE_C, new IdentityRefuted(NODE_C, NODE_A));
            protocol.setMembershipEligibility(peer -> !NODE_C.equals(peer) || evenRound);
            protocol.addSeedMember(NODE_C, ADDR_C);
            protocol.reportSelfQuorum(round % 2 == 0
                                      ? QuorumReport.LOST
                                      : QuorumReport.HELD);
            protocol.announceJoin(SELF_INFO, "cluster", 1L, List.of());
            tokens.admit(NODE_C, round % 2 == 0
                                 ? TOKEN
                                 : OTHER_TOKEN);
        }
    }

    private static void exerciseTestSeams(SwimProtocol protocol) {
        protocol.putMemberForTest(NODE_C, ADDR_C, MemberState.SUSPECT);
        protocol.removeMemberForTest(NODE_C);
        protocol.joinAcknowledgedForTest();
        protocol.putDeathRecordForTest(NODE_C, 0L, TOKEN, 0L);
        protocol.expireSuspectMembersForTest(System.currentTimeMillis() + 3_600_000L);
        protocol.sweepAddressCandidatesForTest(System.currentTimeMillis() + 3_600_000L);
        protocol.probeRoundForTest();
        protocol.probeOnceForTest();
        protocol.latchSelfIsolationForTest(() -> true);
        protocol.announceFromPinnedSourceForTest(ADDR_C, Announce.announce(INFO_C, "cluster", 99L, TOKEN));
    }

    private static MembershipUpdate alive(NodeId peer, long incarnation) {
        return update(peer, MemberState.ALIVE, incarnation);
    }

    private static MembershipUpdate labelled(NodeId peer) {
        return MembershipUpdate.membershipUpdate(peer, MemberState.ALIVE, 0, addressOf(peer), 0L, LABELS);
    }

    private static MembershipUpdate update(NodeId peer, MemberState state, long incarnation) {
        return MembershipUpdate.membershipUpdate(peer, state, incarnation, addressOf(peer));
    }

    private static InetSocketAddress addressOf(NodeId peer) {
        return NODE_A.equals(peer)
               ? ADDR_A
               : NODE_B.equals(peer)
                 ? ADDR_B
                 : ADDR_C;
    }

    private static SwimProtocol protocol(SwimMembershipListener listener) {
        var config = swimConfig(timeSpan(20).millis(),
                                timeSpan(20).millis(),
                                3,
                                timeSpan(100).millis(),
                                8,
                                timeSpan(0).millis()).withJoinGrace(timeSpan(0).millis());

        return SwimProtocol.swimProtocol(config,
                                         new SilentTransport(),
                                         listener,
                                         SELF_ID,
                                         SELF_ADDR,
                                         () -> false,
                                         _ -> false)
                           .unwrap();
    }

    private record FaultyRecordingListener(Set<NodeId> faulty) implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}

        @Override public void onMemberSuspect(SwimMember member) {}

        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {
            faulty.add(member.nodeId());
        }

        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
