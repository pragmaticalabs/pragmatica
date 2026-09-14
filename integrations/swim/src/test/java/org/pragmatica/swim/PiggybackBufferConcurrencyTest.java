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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;

import static org.assertj.core.api.Assertions.assertThat;

/// #1151: `PiggybackBuffer.peekUpdates` used to drain the deque and re-add the survivors after the
/// loop, unlocked, so every other thread saw an EMPTY buffer for the width of the peek. The probe
/// tick peeks every period; the transport thread peeks on every inbound ping; `expireFaultyUpdates`
/// runs on the rejoin path. Two consequences, each pinned here:
/// - a reader (`faultyCount`) observed 0 while a FAULTY entry was buffered and never evicted;
/// - the isolation-era expiry racing a peek missed the in-flight FAULTY verdict, which was then
///   re-added and gossiped into the healed cluster (the S06 hazard the expiry exists to prevent).
class PiggybackBufferConcurrencyTest {
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);

    private static final int READS = 1_000_000;
    private static final int ROUNDS = 20_000;

    @Test
    void faultyCount_neverReadsZero_whileAFaultyEntryIsBuffered_underAConcurrentPeekTicker() throws InterruptedException {
        // maxSize 1_000_000 → eviction after 3_000_000 disseminations; the ticker never gets there,
        // so the FAULTY entry is present for the whole run and every 0 is a mid-peek observation.
        var buffer = PiggybackBuffer.piggybackBuffer(1_000_000);
        buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A));
        buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.SUSPECT, 1, ADDR_B));

        var stop = new AtomicBoolean();
        var peeks = new AtomicLong();
        var ticker = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                buffer.peekUpdates(8);
                peeks.incrementAndGet();
            }
        });

        var zeroReads = 0;

        for (int read = 0; read < READS; read++) {
            if (buffer.faultyCount() == 0) {
                zeroReads++;
            }
        }
        stop.set(true);
        ticker.join();

        assertThat(peeks.get()).as("control: the ticker peeked concurrently with the reads").isPositive();
        assertThat(buffer.faultyCount()).as("control: the FAULTY entry was never evicted").isEqualTo(1);
        assertThat(zeroReads).as("faultyCount() read 0 while a FAULTY entry was buffered (of %d reads, %d concurrent peeks)",
                                 READS,
                                 peeks.get())
                             .isZero();
    }

    @Test
    void expireFaultyUpdates_racingAPeek_dropsTheFaultyVerdict_everyRound() throws InterruptedException {
        var escaped = 0;
        var droppedNotOne = 0;

        for (int round = 0; round < ROUNDS; round++) {
            var buffer = PiggybackBuffer.piggybackBuffer(8);
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 1, ADDR_B));

            var go = new CountDownLatch(1);
            var peeker = Thread.ofPlatform().start(() -> {
                awaitStart(go);
                buffer.peekUpdates(8);
            });
            go.countDown();
            Thread.onSpinWait();

            var dropped = buffer.expireFaultyUpdates();

            peeker.join();

            // Whichever order the two ran in, exactly one FAULTY entry existed and must be gone.
            if (dropped != 1) {
                droppedNotOne++;
            }
            if (buffer.faultyCount() > 0) {
                escaped++;
            }
        }

        assertThat(escaped).as("FAULTY verdict still buffered after expireFaultyUpdates (of %d rounds; dropped != 1 in %d)",
                               ROUNDS,
                               droppedNotOne)
                           .isZero();
        assertThat(droppedNotOne).as("expireFaultyUpdates reported a count other than 1 (of %d rounds)", ROUNDS)
                                 .isZero();
    }

    private static void awaitStart(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
