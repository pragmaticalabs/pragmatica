/*
 *  Copyright (c) 2020-2026 Sergiy Yevtushenko.
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #3754: `stop()` ends the protocol's lifetime, so no task it scheduled produces an effect after the stop. Four sites
/// schedule: the probe tick, the probe timeout (and its re-arm), the isolated-and-alone seed ANNOUNCE and the join-announce
/// loop. A fake scheduler records each enqueue and the test fires the wrapper by hand, so a task that the scheduler
/// would have run (or had already started) is run after the real `stop()`, in production order.
///
/// Per site: (a) a task scheduled before the stop never runs its body after it; (b) a body already past the wrapper's read of
/// the lifetime when `stop()` runs does not produce its effect (the tick and the probe timeout queue on the protocol's
/// serial executor, held here; the join-announce loop is mid-attempt at its first seed). The seed ANNOUNCE has no (b): its
/// body's first statement is the send, so the wrapper's read is the only window there is. Every effect assertion has a
/// control arm that produces the same effect without a stop, so an empty read cannot come from a dead setup.
class SwimStopLifetimeTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9300);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9301);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9302);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9303);
    private static final InetSocketAddress SEED_1 = new InetSocketAddress("127.0.0.1", 9401);
    private static final InetSocketAddress SEED_2 = new InetSocketAddress("127.0.0.1", 9402);
    private static final NodeInfo SELF_INFO = NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9000));
    private static final long TOKEN = 0x5E1FL;

    // ---- the probe tick ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void tick_scheduledBeforeTheStop_neverRunsItsBodyAfterIt() {
        assertThat(pingsFromAFiredTick(false)).as("control: a tick that is fired without a stop probes the member")
                                              .isEqualTo(1);
        assertThat(pingsFromAFiredTick(true)).as("a tick scheduled before the stop never probes after it")
                                             .isZero();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void tick_inFlightAtTheStop_doesNotProbe() throws InterruptedException {
        assertThat(pingsFromATickQueuedBehindTheHeldExecutor(false)).as("control: a queued tick with no stop probes the member")
                                                                    .isEqualTo(1);
        assertThat(pingsFromATickQueuedBehindTheHeldExecutor(true)).as("a tick dispatched before the stop, run after it, probes nothing")
                                                                   .isZero();
    }

    private static long pingsFromAFiredTick(boolean stop) {
        var fixture = new Fixture(false);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        fixture.protocol.start();
        var tick = fixture.scheduler.periodic();

        if (stop) {
            fixture.protocol.stop();
        }
        tick.wrapper().run();

        return fixture.transport.count(Ping.class);
    }

    private static long pingsFromATickQueuedBehindTheHeldExecutor(boolean stop) throws InterruptedException {
        var fixture = new Fixture(true);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        fixture.protocol.start();
        var tick = fixture.scheduler.periodic();

        try (var hold = new ExecutorHold(fixture.protocol)) {
            tick.wrapper().run();
            if (stop) {
                fixture.protocol.stop();
            }
        }
        fixture.protocol.onStateExecutorForTest(() -> {});

        return fixture.transport.count(Ping.class);
    }

    // ---- the probe timeout, direct and indirect ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void probeTimeout_scheduledBeforeTheStop_neverSendsAPingReqAfterIt() {
        assertThat(pingReqsFromAFiredProbeTimeout(false)).as("control: a probe timeout that is fired without a stop sends PingReqs")
                                                         .isEqualTo(2);
        assertThat(pingReqsFromAFiredProbeTimeout(true)).as("a probe timeout scheduled before the stop never sends a PingReq after it")
                                                        .isZero();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void probeTimeout_inFlightAtTheStop_sendsNoPingReq() throws InterruptedException {
        assertThat(pingReqsFromAProbeTimeoutQueuedBehindTheHeldExecutor(false)).as("control: a queued probe timeout with no stop sends PingReqs")
                                                                               .isEqualTo(2);
        assertThat(pingReqsFromAProbeTimeoutQueuedBehindTheHeldExecutor(true)).as("a probe timeout dispatched before the stop, run after it, sends no PingReq")
                                                                              .isZero();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void probeTimeout_reArmedByTheFirstTimeout_isNeverRunAfterTheStop() {
        assertThat(suspectsFromTheReArmedTimeout(false)).as("control: the re-armed timeout, fired without a stop, suspects the member")
                                                        .isEqualTo(1);
        assertThat(suspectsFromTheReArmedTimeout(true)).as("the timeout re-armed before the stop raises no suspicion after it")
                                                       .isZero();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void probeTimeout_secondTimeoutInFlightAtTheStop_raisesNoSuspicion() throws InterruptedException {
        assertThat(suspectsFromTheSecondTimeoutQueuedBehindTheHeldExecutor(false)).as("control: a queued second timeout with no stop suspects the member")
                                                                                  .isEqualTo(1);
        assertThat(suspectsFromTheSecondTimeoutQueuedBehindTheHeldExecutor(true)).as("a second timeout dispatched before the stop, run after it, raises no suspicion")
                                                                                 .isZero();
    }

    private static long pingReqsFromAFiredProbeTimeout(boolean stop) {
        var fixture = threeMembersOneProbe(false);
        var timeout = fixture.scheduler.oneShot();

        if (stop) {
            fixture.protocol.stop();
        }
        timeout.wrapper().run();

        return fixture.transport.count(PingReq.class);
    }

    private static long pingReqsFromAProbeTimeoutQueuedBehindTheHeldExecutor(boolean stop) throws InterruptedException {
        var fixture = threeMembersOneProbe(true);
        var timeout = fixture.scheduler.oneShot();

        try (var hold = new ExecutorHold(fixture.protocol)) {
            timeout.wrapper().run();
            if (stop) {
                fixture.protocol.stop();
            }
        }
        fixture.protocol.onStateExecutorForTest(() -> {});

        return fixture.transport.count(PingReq.class);
    }

    private static long suspectsFromTheReArmedTimeout(boolean stop) {
        var fixture = threeMembersOneProbe(false);

        fixture.scheduler.oneShot().wrapper().run();
        var reArmed = fixture.scheduler.lastOneShot();

        if (stop) {
            fixture.protocol.stop();
        }
        reArmed.wrapper().run();

        return fixture.listener.suspects.size();
    }

    private static long suspectsFromTheSecondTimeoutQueuedBehindTheHeldExecutor(boolean stop) throws InterruptedException {
        var fixture = threeMembersOneProbe(true);

        fixture.scheduler.oneShot().wrapper().run();
        var reArmed = fixture.scheduler.lastOneShot();

        try (var hold = new ExecutorHold(fixture.protocol)) {
            reArmed.wrapper().run();
            if (stop) {
                fixture.protocol.stop();
            }
        }
        fixture.protocol.onStateExecutorForTest(() -> {});

        return fixture.listener.suspects.size();
    }

    /// Three ALIVE members, one direct probe sent to the first (A, least-recently-probed, ties by id); its timeout is the
    /// one one-shot task on the scheduler. The protocol is not started, so the tick is not armed.
    private static Fixture threeMembersOneProbe(boolean held) {
        var fixture = new Fixture(held);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_C, ADDR_C, MemberState.ALIVE);
        fixture.protocol.probeOnceForTest();
        fixture.transport.sent.clear();

        return fixture;
    }

    // ---- the isolated-and-alone seed ANNOUNCE ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void seedAnnounce_scheduledBeforeTheStop_neverSendsAfterIt() {
        assertThat(announcesFromAFiredSeedAnnounce(false)).as("control: a seed ANNOUNCE that is fired without a stop reaches the seed")
                                                          .isEqualTo(1);
        assertThat(announcesFromAFiredSeedAnnounce(true)).as("a seed ANNOUNCE scheduled before the stop is never sent")
                                                         .isZero();
    }

    private static long announcesFromAFiredSeedAnnounce(boolean stop) {
        var fixture = new Fixture(false);

        fixture.protocol.announceJoin(SELF_INFO, "c", 1L, TOKEN, List.of(SEED_1));
        fixture.protocol.joinAcknowledgedForTest();
        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.FAULTY);
        fixture.protocol.latchSelfIsolationForTest(() -> true);
        fixture.protocol.removeMemberForTest(NODE_A);
        fixture.protocol.probeRoundForTest();
        fixture.transport.sent.clear();
        var announce = fixture.scheduler.oneShot();

        if (stop) {
            fixture.protocol.stop();
        }
        announce.wrapper().run();

        return fixture.transport.count(Announce.class);
    }

    // ---- the join-announce loop ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void announceLoop_scheduledBeforeTheStop_neverRunsItsBodyAfterIt() {
        assertThat(announcesFromAFiredAnnounceLoop(false)).as("control: a loop attempt fired without a stop announces to the seed")
                                                          .isEqualTo(1);
        assertThat(announcesFromAFiredAnnounceLoop(true)).as("an announce loop armed before the stop never announces after it")
                                                         .isZero();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void announceLoop_attemptInFlightAtTheStop_stopsAtTheSeedItHasReached() throws InterruptedException {
        assertThat(secondSeedAnnounces(false)).as("control: an attempt with no stop announces to its second seed")
                                              .isEqualTo(1);
        assertThat(secondSeedAnnounces(true)).as("an attempt inside its first seed's send when the stop runs never reaches the second seed")
                                             .isZero();
    }

    private static long announcesFromAFiredAnnounceLoop(boolean stop) {
        var fixture = new Fixture(false);

        fixture.protocol.announceJoin(SELF_INFO, "c", 1L, TOKEN, List.of(SEED_1));
        var loop = fixture.scheduler.periodic();

        if (stop) {
            fixture.protocol.stop();
        }
        loop.wrapper().run();

        return fixture.transport.count(Announce.class);
    }

    private static long secondSeedAnnounces(boolean stop) throws InterruptedException {
        var gate = new FirstSendGate();
        var fixture = new Fixture(false, gate);

        fixture.protocol.announceJoin(SELF_INFO, "c", 1L, TOKEN, List.of(SEED_1, SEED_2));
        var loop = fixture.scheduler.periodic();
        var attempt = new Thread(() -> loop.wrapper().run(), "announce-attempt");

        attempt.start();
        assertThat(gate.entered.await(10, SECONDS)).as("the attempt reached its first seed's send").isTrue();
        if (stop) {
            fixture.protocol.stop();
        }
        gate.release.countDown();
        attempt.join(10_000);
        assertThat(attempt.isAlive()).as("the attempt finished once released").isFalse();

        return fixture.transport.countTo(Announce.class, SEED_2);
    }

    // ---- the probe timeout of a real tick (#3763 x11) ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void probeTimeout_scheduledByARealTick_sendsNoPingReqAfterTheStop() {
        assertThat(pingReqsFromTheTimeoutOfARealTick(false)).as("control: the timeout of a tick's probe, fired without a stop, sends PingReqs")
                                                            .isEqualTo(2);
        assertThat(pingReqsFromTheTimeoutOfARealTick(true)).as("the timeout of a tick's probe is on the tick's lifetime: after the stop it sends no PingReq")
                                                           .isZero();
    }

    /// Unlike [#threeMembersOneProbe], the probe is sent by the tick the protocol armed itself, so the lifetime the tick hands to
    /// `probeTarget` is the one under test; `probeRoundForTest` reads the protocol's current lifetime and cannot see a wrong hand-off.
    private static long pingReqsFromTheTimeoutOfARealTick(boolean stop) {
        var fixture = new Fixture(false);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_C, ADDR_C, MemberState.ALIVE);
        assertThat(fixture.protocol.start().isSuccess()).as("the protocol started").isTrue();
        fixture.scheduler.periodic().wrapper().run();
        var timeout = fixture.scheduler.oneShot();

        fixture.transport.sent.clear();
        if (stop) {
            fixture.protocol.stop();
        }
        timeout.wrapper().run();

        return fixture.transport.count(PingReq.class);
    }

    // ---- the membership-view request and the announce cap ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void requestMembershipView_afterTheStop_sendsNothing() {
        assertThat(announcesFromAMembershipViewRequest(false)).as("control: a membership-view request without a stop reaches the target")
                                                              .isEqualTo(1);
        assertThat(announcesFromAMembershipViewRequest(true)).as("a membership-view request after the stop is refused")
                                                             .isZero();
    }

    private static long announcesFromAMembershipViewRequest(boolean stop) {
        var fixture = new Fixture(false);

        if (stop) {
            fixture.protocol.stop();
        }
        fixture.protocol.requestMembershipView(SELF_INFO, "c", List.of(SEED_1));

        return fixture.transport.count(Announce.class);
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void announceLoop_atMaxAttempts_cancelsItself() {
        var fixture = new Fixture(false);

        fixture.protocol.announceJoin(SELF_INFO, "c", 1L, TOKEN, List.of(SEED_1));
        var loop = fixture.scheduler.periodic();

        for (var attempt = 1; attempt < 60; attempt++) {
            loop.wrapper().run();
        }
        assertThat(loop.future().isCancelled()).as("control: the loop is still armed after 59 attempts").isFalse();
        loop.wrapper().run();

        assertThat(fixture.transport.count(Announce.class)).as("instrument: one ANNOUNCE per attempt").isEqualTo(60);
        assertThat(loop.future().isCancelled()).as("the 60th attempt cancels the loop itself").isTrue();
    }

    // ---- restart ----

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void restart_probeInFlightAtTheStop_doesNotGateTheHintFlooredProbe() {
        assertThat(hintProbesAfterRestart(false)).as("control: with no probe in flight at the stop, the hint-floored suspect is probed")
                                                 .isEqualTo(1);
        assertThat(hintProbesAfterRestart(true)).as("a probe in flight at the stop is not a pending probe of the next activation")
                                                .isEqualTo(1);
    }

    /// The peer answers the first tick's probe (so it has been seen healthy), the stop ends the lifetime with the
    /// second tick's probe still pending (`inFlight`), the protocol restarts and a transport hint then floors the peer's
    /// suspicion: [SwimProtocol#probeFlooredSuspect] probes it only when no probe of it is pending.
    private static long hintProbesAfterRestart(boolean inFlight) {
        var fixture = new Fixture(false);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        assertThat(fixture.protocol.start().isSuccess()).as("the protocol started").isTrue();
        var tick = fixture.scheduler.periodic();

        tick.wrapper().run();
        fixture.protocol.onMessage(ADDR_A, Ack.ack(NODE_A, fixture.transport.lastPingSequence(), List.of()));
        if (inFlight) {
            tick.wrapper().run();
        }
        assertThat(fixture.protocol.stop().isSuccess()).as("the protocol stopped").isTrue();
        assertThat(fixture.protocol.start().isSuccess()).as("the protocol restarted").isTrue();
        fixture.transport.sent.clear();
        fixture.protocol.recordTransportHint(NODE_A,
                                             new TransportObservation.PeerUnreachable(NODE_A,
                                                                                      Causes.cause("ClusterSync missed pongs"),
                                                                                      TransportObservation.HintOrigin.PEER_UNRESPONSIVE));

        return fixture.transport.countTo(Ping.class, ADDR_A);
    }

    /// The lifetime renewal end to end: the stop cancels the first activation's tick, the restart arms a second one on a
    /// fresh lifetime, the first tick's wrapper stays dead, the second tick's probe and its timeout run, and the second stop
    /// ends the renewed lifetime too.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void restart_renewsTheLifetime_theOldTickStaysDeadAndTheNewOneEndsWithTheNextStop() {
        var fixture = new Fixture(false);

        fixture.protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);
        fixture.protocol.putMemberForTest(NODE_C, ADDR_C, MemberState.ALIVE);
        assertThat(fixture.protocol.start().isSuccess()).as("the protocol started").isTrue();
        var oldTick = fixture.scheduler.periodicAt(0);

        fixture.protocol.stop();
        assertThat(oldTick.future().isCancelled()).as("the stop cancelled the first activation's tick").isTrue();
        assertThat(fixture.protocol.start().isSuccess()).as("the protocol restarted").isTrue();
        assertThat(fixture.scheduler.periodicCount()).as("the restart armed a second tick").isEqualTo(2);
        var newTick = fixture.scheduler.periodicAt(1);

        oldTick.wrapper().run();
        assertThat(fixture.transport.count(Ping.class)).as("the first activation's tick stays dead after the restart").isZero();
        newTick.wrapper().run();
        assertThat(fixture.transport.count(Ping.class)).as("the renewed lifetime's tick probes").isEqualTo(1);
        fixture.scheduler.oneShot().wrapper().run();
        assertThat(fixture.transport.count(PingReq.class)).as("the renewed lifetime's probe timeout runs").isEqualTo(2);

        var reArmed = fixture.scheduler.lastOneShot();

        fixture.protocol.stop();
        reArmed.wrapper().run();
        assertThat(fixture.listener.suspects).as("the second stop ended the renewed lifetime: its re-armed timeout raises no suspicion").isEmpty();
    }

    // ---- fixtures ----

    private static SwimConfig config() {
        return swimConfig(timeSpan(50).millis(),
                          timeSpan(50).millis(),
                          2,
                          timeSpan(1000).millis(),
                          8,
                          timeSpan(0).millis()).withJoinGrace(timeSpan(0).millis());
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final RecordingTransport transport;
        final RecordingListener listener = new RecordingListener();
        final SwimProtocol protocol;

        Fixture(boolean held) {
            this(held, new RecordingTransport());
        }

        Fixture(boolean held, RecordingTransport transport) {
            this.transport = transport;
            this.protocol = held
                            ? SwimTestProtocols.swimProtocolOnLifetimesHeldExecutor(config(),
                                                                                    transport,
                                                                                    listener,
                                                                                    SELF_ID,
                                                                                    SELF_ADDR,
                                                                                    this::lifetime)
                                               .unwrap()
                            : SwimTestProtocols.swimProtocolOnLifetimes(config(),
                                                                        transport,
                                                                        listener,
                                                                        SELF_ID,
                                                                        SELF_ADDR,
                                                                        this::lifetime)
                                               .unwrap();
        }

        private OwnerLifetime lifetime() {
            return OwnerLifetime.ownerLifetime(scheduler);
        }
    }

    /// Holds the protocol's serial executor from another thread until closed, so a task dispatched meanwhile stays queued.
    private static final class ExecutorHold implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread holder;

        ExecutorHold(SwimProtocol protocol) throws InterruptedException {
            holder = new Thread(() -> protocol.onStateExecutorForTest(this::holdUntilReleased), "executor-hold");
            holder.start();
            assertThat(entered.await(10, SECONDS)).as("the executor is held").isTrue();
        }

        private void holdUntilReleased() {
            entered.countDown();
            awaitQuietly(release);
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            holder.join(10_000);
            assertThat(holder.isAlive()).as("the holder finished once released").isFalse();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /// Blocks the first send of a message to SEED_1 until released, then records every send.
    private static final class FirstSendGate extends RecordingTransport {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            if (target.equals(SEED_1) && blocked.compareAndSet(false, true)) {
                entered.countDown();
                awaitQuietly(release);
            }

            return super.send(target, message);
        }
    }

    private static class RecordingTransport implements SwimTransport {
        record Sent(InetSocketAddress target, SwimMessage message) {}

        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

        long count(Class<? extends SwimMessage> type) {
            return sent.stream().filter(entry -> type.isInstance(entry.message())).count();
        }

        long lastPingSequence() {
            synchronized (sent) {
                return sent.stream()
                           .filter(entry -> entry.message() instanceof Ping)
                           .map(entry -> ((Ping) entry.message()).sequence())
                           .reduce((_, later) -> later)
                           .orElseThrow();
            }
        }

        long countTo(Class<? extends SwimMessage> type, InetSocketAddress target) {
            return sent.stream()
                       .filter(entry -> type.isInstance(entry.message()) && entry.target().equals(target))
                       .count();
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sent.add(new Sent(target, message));

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    private static final class RecordingListener implements SwimMembershipListener {
        final List<NodeId> suspects = Collections.synchronizedList(new ArrayList<>());

        @Override public void onMemberJoined(SwimMember member) {}

        @Override public void onMemberSuspect(SwimMember member) {
            suspects.add(member.nodeId());
        }

        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}

        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    /// Records each enqueue and returns a future that records its cancel. It never runs anything: the test fires wrappers.
    private static final class FakeScheduler implements OwnerLifetime.Seam {
        record Task(Runnable wrapper, TimeSpan delay, Option<TimeSpan> period, FakeFuture future) {}

        private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
            var future = new FakeFuture();

            tasks.add(new Task(wrapper, initialDelay, period, future));

            return future;
        }

        Task periodic() {
            return only(task -> task.period().isPresent());
        }

        Task periodicAt(int index) {
            synchronized (tasks) {
                return tasks.stream().filter(task -> task.period().isPresent()).toList().get(index);
            }
        }

        int periodicCount() {
            synchronized (tasks) {
                return (int) tasks.stream().filter(task -> task.period().isPresent()).count();
            }
        }

        Task oneShot() {
            return only(task -> task.period().isEmpty());
        }

        Task lastOneShot() {
            synchronized (tasks) {
                return tasks.stream().filter(task -> task.period().isEmpty()).reduce((_, later) -> later).orElseThrow();
            }
        }

        private Task only(Predicate<Task> kind) {
            synchronized (tasks) {
                var matching = tasks.stream().filter(kind).toList();

                assertThat(matching).as("exactly one task of the kind was enqueued").hasSize(1);

                return matching.getFirst();
            }
        }
    }

    private static final class FakeFuture implements ScheduledFuture<Object> {
        private volatile boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
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
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
