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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimSelfIsolationLatchAtomicityTest.SilentListener;
import org.pragmatica.swim.SwimSelfIsolationLatchAtomicityTest.SilentTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// #3529: the suspicion expiry reads the member, decides FAULTY and stores it; an ALIVE refutation at a higher
/// incarnation delivered by [SwimProtocol#onMessage] on another thread must never be overwritten by that store.
/// Every SWIM state mutation runs on one serial executor, so the refutation is ordered either before the expiry's
/// read (no FAULTY at all) or after its store (FAULTY at incarnation 0, then refuted): either way the member ends
/// ALIVE at incarnation 1. Without the confinement the refutation lands between the read and the store, and the
/// store overwrites it with FAULTY at incarnation 0.
///
/// The read-to-store window is held open deterministically: the membership-eligibility predicate is consulted
/// inside the member store, so a hook there parks the expiry until the refuter has either finished (nothing
/// serialises it with the expiry) or parked behind it.
class SwimExpiryRefutationRaceTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9200);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9201);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9202);
    private static final long WAIT_MS = 10_000;
    private static final long FAR_FUTURE_MS = 3_600_000;
    private static final int STRESS_ROUNDS = 300;

    @Test
    void expiry_refutationInsideTheMemberStore_endsAliveAtIncarnationOne() throws InterruptedException {
        var hook = new StoreHook();
        var protocol = suspectedProtocol(hook);

        runRaceInsideStore(protocol, hook, List.of(aliveOfNodeA(1, 10L)));

        assertThat(hook.fired.get()).as("control: the hook held the expiry inside its member store").isTrue();
        assertThat(stateOfA(protocol)).as("a refutation at incarnation 1 survives the expiry's FAULTY store")
                                      .isEqualTo("ALIVE@1");
    }

    @Test
    void expiry_refutationAndReSuspicionInsideTheMemberStore_endsSuspectAtIncarnationOneWithAnArmedSuspicion()
        throws InterruptedException {
        var hook = new StoreHook();
        var protocol = suspectedProtocol(hook);

        runRaceInsideStore(protocol, hook, List.of(aliveOfNodeA(1, 10L), suspectOfNodeA(1, 11L)));

        assertThat(hook.fired.get()).as("control: the hook held the expiry inside its member store").isTrue();
        assertThat(stateOfA(protocol)).as("the re-suspicion at incarnation 1 survives the expiry's FAULTY store")
                                      .isEqualTo("SUSPECT@1");
        assertThat(protocol.suspectTimestampForTest(NODE_A).isPresent())
            .as("the re-armed suspicion is live, so the member can still ripen to FAULTY")
            .isTrue();
    }

    /// The same interleaving left to the scheduler: both threads leave a spin barrier together, many rounds.
    /// Whatever order the executor picks, the refutation is never lost.
    @Test
    void expiry_refutationRacingFreely_neverLosesTheRefutation() throws InterruptedException {
        var lost = new AtomicInteger();

        for (var round = 0; round < STRESS_ROUNDS; round++) {
            var protocol = suspectedProtocol(new StoreHook());
            var ready = new AtomicInteger();
            var go = new AtomicBoolean(false);
            var expiry = new Thread(() -> spinThen(ready, go, () -> protocol.expireSuspectMembersForTest(farFuture())),
                                    "race-expiry");
            var refuter = new Thread(() -> spinThen(ready, go, () -> protocol.onMessage(ADDR_A, aliveOfNodeA(1, 10L))),
                                     "race-refuter");

            expiry.start();
            refuter.start();
            awaitCount(ready, 2);
            go.set(true);
            expiry.join(WAIT_MS);
            refuter.join(WAIT_MS);
            assertThat(expiry.isAlive() || refuter.isAlive()).as("round %d finished within the deadline", round)
                                                            .isFalse();
            if (!"ALIVE@1".equals(stateOfA(protocol))) {
                lost.incrementAndGet();
            }
        }

        assertThat(lost.get()).as("rounds that ended with the refutation overwritten").isZero();
    }

    private static void runRaceInsideStore(SwimProtocol protocol, StoreHook hook, List<Ping> refutation)
        throws InterruptedException {
        hook.armed.set(true);
        var expiry = new Thread(() -> protocol.expireSuspectMembersForTest(farFuture()), "race-expiry");

        hook.expiryThread.set(expiry);
        expiry.start();
        assertThat(hook.inside.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("control: the expiry reached its member store")
                                                                    .isTrue();

        var refuter = new Thread(() -> refutation.forEach(ping -> protocol.onMessage(ADDR_A, ping)), "race-refuter");

        refuter.start();
        awaitParkedOrDone(refuter);
        hook.release.countDown();
        expiry.join(WAIT_MS);
        refuter.join(WAIT_MS);
        assertThat(expiry.isAlive() || refuter.isAlive()).as("both threads finished within the deadline").isFalse();
    }

    /// A member A that is SUSPECT at incarnation 0 with one live suspicion, accused by B, never HEALTHY.
    private static SwimProtocol suspectedProtocol(StoreHook hook) {
        var config = swimConfig(timeSpan(20).millis(),
                                timeSpan(20).millis(),
                                3,
                                timeSpan(100).millis(),
                                8,
                                timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
        var protocol = SwimProtocol.swimProtocol(config,
                                                 new SilentTransport(),
                                                 new SilentListener(),
                                                 SELF_ID,
                                                 SELF_ADDR,
                                                 () -> false,
                                                 _ -> false)
                                   .unwrap();

        protocol.setMembershipEligibility(hook::eligible);
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.ALIVE);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.ALIVE);
        protocol.onMessage(ADDR_B, new Ping(NODE_B, 1L, List.of(update(MemberState.SUSPECT, 0))));
        assertThat(stateOfA(protocol)).as("control: A is SUSPECT at incarnation 0").isEqualTo("SUSPECT@0");
        assertThat(protocol.suspectTimestampForTest(NODE_A).isPresent()).as("control: A's suspicion is armed").isTrue();

        return protocol;
    }

    private static String stateOfA(SwimProtocol protocol) {
        var member = protocol.members().get(NODE_A);

        return member == null
               ? "ABSENT"
               : member.state() + "@" + member.incarnation();
    }

    private static Ping aliveOfNodeA(long incarnation, long sequence) {
        return new Ping(NODE_A, sequence, List.of(update(MemberState.ALIVE, incarnation)));
    }

    private static Ping suspectOfNodeA(long incarnation, long sequence) {
        return new Ping(NODE_B, sequence, List.of(update(MemberState.SUSPECT, incarnation)));
    }

    private static MembershipUpdate update(MemberState state, long incarnation) {
        return MembershipUpdate.membershipUpdate(NODE_A, state, incarnation, ADDR_A);
    }

    private static long farFuture() {
        return System.currentTimeMillis() + FAR_FUTURE_MS;
    }

    private static void spinThen(AtomicInteger ready, AtomicBoolean go, Runnable action) {
        ready.incrementAndGet();
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);

        while (!go.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        action.run();
    }

    /// Returns when `expected` threads have checked in or the deadline passes; a miss is silent here, the caller's
    /// own assertion decides (the spinning threads proceed after their own deadline).
    private static void awaitCount(AtomicInteger counter, int expected) {
        pollUntil(counter::get, count -> count >= expected, Duration.ofMillis(WAIT_MS));
    }

    /// The refuter either finishes (nothing serialises it with the held expiry) or parks waiting for its turn;
    /// both mean it has done everything it can do while the expiry is held inside its store. A miss is silent here,
    /// the caller's own assertion decides.
    private static void awaitParkedOrDone(Thread thread) {
        pollUntil(() -> thread.isAlive() && thread.getState() == Thread.State.RUNNABLE,
                  busy -> !busy,
                  Duration.ofMillis(WAIT_MS));
    }

    /// Membership eligibility that admits everyone and, once armed, parks the expiry thread the first time it is
    /// consulted there: inside [SwimProtocol]'s member store, after the expiry read the member and decided FAULTY.
    private static final class StoreHook {
        final AtomicBoolean armed = new AtomicBoolean(false);
        final AtomicBoolean fired = new AtomicBoolean(false);
        final AtomicReference<Thread> expiryThread = new AtomicReference<>();
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        boolean eligible(NodeId peer) {
            if (armed.get() && NODE_A.equals(peer) && Thread.currentThread() == expiryThread.get()
                && fired.compareAndSet(false, true)) {
                inside.countDown();
                awaitRelease();
            }

            return true;
        }

        private void awaitRelease() {
            try {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
