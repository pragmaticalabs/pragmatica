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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #1990: the all-peers-FAULTY check and the self-isolation latch write are one atomic step against a
/// revival's "store ALIVE, then clear the latch". Without it a revival that lands between the check and the
/// write leaves the latch set while a peer is ALIVE (a stale latch: measured 2 in 400,000 rounds naturally,
/// 104 with the window widened). The window is held open here deterministically by parking the check
/// after it has read the members, instead of hoping a scheduler stall lands in it.
class SwimSelfIsolationLatchAtomicityTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9102);
    private static final long WAIT_MS = 10_000;

    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        var config = swimConfig(timeSpan(20).millis(),
                                timeSpan(20).millis(),
                                3,
                                timeSpan(100).millis(),
                                8,
                                timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());

        protocol = SwimProtocol.swimProtocol(config, new SilentTransport(), new SilentListener(), SELF_ID, SELF_ADDR, () -> false)
                               .unwrap();
    }

    /// The race: thread D has read "every peer FAULTY" and is about to write the latch; a verified revival of
    /// NODE_A (state stored ALIVE, then the latch cleared) runs inside that window. Correct end state: no latch,
    /// because a peer is ALIVE. With the check and the write unguarded the revival's clear sees a clear latch,
    /// does nothing, and D then sets it.
    @Test
    void revivalInsideTheCheckToWriteWindow_leavesNoStaleLatch() throws InterruptedException {
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.FAULTY);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.FAULTY);
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var detector = new Thread(() -> protocol.latchSelfIsolation(() -> allFaultyThenHold(checked, release)), "latch-detector");

        detector.start();
        assertThat(checked.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the check read all peers FAULTY and is parked").isTrue();

        var reviver = new Thread(() -> protocol.onMessage(ADDR_A, revivalOfNodeA()), "latch-reviver");

        reviver.start();
        awaitBlockedOrDone(reviver);
        release.countDown();
        detector.join(WAIT_MS);
        reviver.join(WAIT_MS);

        assertThat(protocol.members().get(NODE_A).state()).as("control: the revival took effect").isEqualTo(MemberState.ALIVE);
        assertThat(protocol.selfIsolatedForTest()).as("latch must not be set while a peer is ALIVE").isFalse();
    }

    /// The other side of the guarantee, which the guard must not break: the last of two peers to go FAULTY
    /// sees the earlier store, so the latch is set even when the two writers' checks overlap. Holds with or
    /// without the guard; it fails if a "fix" skips the check when the guard is contended.
    @Test
    void lastTwoPeersFaultyConcurrently_stillLatches() throws InterruptedException {
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.SUSPECT);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.SUSPECT);
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new Thread(() -> faultyThenCheck(NODE_A, ADDR_A, () -> allFaultyThenHold(checked, release)), "latch-first");

        first.start();
        assertThat(checked.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the first check ran and saw NODE_B not FAULTY").isTrue();

        var second = new Thread(() -> faultyThenCheck(NODE_B, ADDR_B, () -> allFaulty()), "latch-second");

        second.start();
        awaitBlockedOrDone(second);
        release.countDown();
        first.join(WAIT_MS);
        second.join(WAIT_MS);

        assertThat(protocol.selfIsolatedForTest()).as("both peers FAULTY, so the latch is set").isTrue();
    }

    @Test
    void noPeers_neverLatches() {
        assertThat(protocol.latchSelfIsolation(() -> true)).as("an empty membership is not an all-peers-FAULTY signature").isFalse();
        assertThat(protocol.selfIsolatedForTest()).isFalse();
    }

    @Test
    void alreadyLatched_secondCheckReportsNothingNew() {
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.FAULTY);

        assertThat(protocol.latchSelfIsolation(this::allFaulty)).as("first check latches").isTrue();
        assertThat(protocol.latchSelfIsolation(this::allFaulty)).as("the latch is already set").isFalse();
        assertThat(protocol.selfIsolatedForTest()).isTrue();
    }

    private void faultyThenCheck(NodeId peer, InetSocketAddress address, BooleanSupplier check) {
        protocol.putMemberForTest(peer, address, MemberState.FAULTY);
        protocol.latchSelfIsolation(check);
    }

    private boolean allFaulty() {
        return protocol.members().values().stream().allMatch(member -> member.state() == MemberState.FAULTY);
    }

    private boolean allFaultyThenHold(CountDownLatch checked, CountDownLatch release) {
        var allFaulty = allFaulty();

        checked.countDown();
        await(release);

        return allFaulty;
    }

    private static Ping revivalOfNodeA() {
        return new Ping(NODE_A, 99L, List.of(MembershipUpdate.membershipUpdate(NODE_A, MemberState.ALIVE, 5, ADDR_A)));
    }

    /// The reviver either finishes (nothing serialises it with the check) or parks on the latch guard; both
    /// mean it has done everything it can do while the check is held open.
    private static void awaitBlockedOrDone(Thread thread) throws InterruptedException {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            Thread.sleep(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class SilentTransport implements SwimTransport {
        @Override public Promise<Unit> start(int port, SwimMessageHandler messageHandler) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    static class SilentListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}

        @Override public void onMemberSuspect(SwimMember member) {}

        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}

        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
