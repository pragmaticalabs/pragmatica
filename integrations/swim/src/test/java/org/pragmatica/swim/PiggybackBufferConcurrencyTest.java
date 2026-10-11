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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

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

    private static final int MAX_SIZE = 1_000_000;
    // Each peek disseminates the entry once and it is evicted at 3 * MAX_SIZE, so a ticker capped at
    // MAX_SIZE peeks can never exhaust it, however the scheduler orders the two threads (#1895).
    private static final int TICKER_PEEKS = MAX_SIZE;
    private static final int TRIALS = 3;
    private static final int ROUNDS = 20_000;

    /// Outcome of one reader/ticker trial. `overlapped` counts the reads during which a peek landed.
    private record Trial(long overlapped, long zeroReads, long peeks, int faultyAfter) {}

    @Test
    void faultyCount_neverReadsZero_whileAFaultyEntryIsBuffered_underAConcurrentPeekTicker() throws InterruptedException {
        // Eviction is impossible by construction (see TICKER_PEEKS), so every trial asserts its controls and
        // that no read saw 0: a zero read is itself proof the read raced a peek, even when the overlap
        // counter missed it (a read wholly inside one peek). Overlap decides only pass versus skip: a
        // reader descheduled until the ticker is done reads an idle buffer and tests nothing, so if no
        // trial overlapped the test is skipped rather than reported green (and not red either: a stalled
        // runner is not a defect).
        var overlapped = new ArrayList<Trial>();

        for (int trial = 0; trial < TRIALS && overlapped.isEmpty(); trial++) {
            var result = runTrial();

            assertThat(result.peeks()).as("control: the ticker completed its peeks").isEqualTo(TICKER_PEEKS);
            assertThat(result.faultyAfter()).as("control: the FAULTY entry was never evicted").isEqualTo(1);
            assertThat(result.zeroReads()).as("faultyCount() read 0 while a FAULTY entry was buffered (trial %d: %d overlapped reads, %d peeks)",
                                              trial,
                                              result.overlapped(),
                                              result.peeks())
                                          .isZero();

            if (result.overlapped() > 0) {
                overlapped.add(result);
            }
        }

        assumeThat(overlapped).as("no reader/ticker overlap in %d trials; nothing was tested", TRIALS).isNotEmpty();
    }

    private static Trial runTrial() throws InterruptedException {
        var buffer = PiggybackBuffer.piggybackBuffer(MAX_SIZE);
        buffer.addUpdate(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A));
        buffer.addUpdate(MembershipUpdate.membershipUpdate(NODE_B, MemberState.SUSPECT, 1, ADDR_B));

        var peeks = new AtomicLong();
        var ticker = Thread.ofPlatform().start(() -> {
            for (int tick = 0; tick < TICKER_PEEKS; tick++) {
                buffer.peekUpdates(8);
                peeks.incrementAndGet();
            }
        });

        var overlapped = 0L;
        var zeroReads = 0L;

        do {
            var before = peeks.get();

            if (buffer.faultyCount() == 0) {
                zeroReads++;
            }
            if (peeks.get() != before) {
                overlapped++;
            }
        } while (ticker.isAlive());
        ticker.join();

        return new Trial(overlapped, zeroReads, peeks.get(), buffer.faultyCount());
    }

    @Test
    void expireFaultyUpdates_racingAPeek_dropsTheFaultyVerdict_everyRound() throws InterruptedException {
        var escaped = 0;
        var droppedNotOne = 0;

        for (int round = 0; round < ROUNDS; round++) {
            var buffer = PiggybackBuffer.piggybackBuffer(8);
            buffer.addUpdate(MembershipUpdate.membershipUpdate(NODE_A, MemberState.FAULTY, 1, ADDR_A));
            buffer.addUpdate(MembershipUpdate.membershipUpdate(NODE_B, MemberState.ALIVE, 1, ADDR_B));

            // Spin-start rather than a latch: a latch wake-up is microseconds, the peek window is
            // nanoseconds, and the pin has to land inside it.
            var start = new AtomicBoolean();
            var peeker = Thread.ofPlatform().start(() -> {
                while (!start.get()) {
                    Thread.onSpinWait();
                }
                buffer.peekUpdates(8);
            });
            start.set(true);

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
}
