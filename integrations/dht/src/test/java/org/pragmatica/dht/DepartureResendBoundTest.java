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

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.DHTRebalancerTest.TimedSend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 I2, the cost of slow acks: a page whose ack takes longer than `resendAfter` (W) is split by silence, but a key already
/// safe is never sent again (the page is settled, a resend carries only open entries, a split keeps only open keys), and a slow but
/// live target's page is not dead at a re-plan (its tenure did not change), so its keys are not paged again. With n keys in one
/// page and every ack delayed by L, each key is sent once per W elapsed before its first ack lands: n * ceil(L / W) key-sends
/// (measured: 64, 128 and 256 for L = W/2, 2W and 3.5W). Before the rule, n = 64 and L = 2W cost 524 key-sends.
///
/// The delay is driven by EVENT ORDER, not by the wall clock (a wall-clock delay of exactly 2W ties with the halves' own
/// timeout at 2W, and the outcome then depends on host load: 128, 160 or 192 key-sends): a key's round is the number of times it
/// was sent before, and the ack of a round-r push is delivered right after every one of its keys has been sent in round
/// r + ceil(L / W) - 1, that is, before that round's silence (W later) can split it again. At the first push of every round the
/// run is re-planned with the view unchanged, so a page awaiting its ack meets a re-plan.
///
/// The skip rule sits at three sites (`isSettled`, the open entries of a resend, the open keys of a split). Each site alone, and
/// the open-entries and split sites together, are redundant with the others (measured: removing any of them leaves the key-sends
/// unchanged); only removing all three together breaks the bound, which these tests catch.
class DepartureResendBoundTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final int KEYS = 64;
    private static final long RESEND_MS = 100;

    @Test
    @Timeout(30)
    void slowAcks_ackWithinOneResendPeriod_eachKeySentOnce() {
        assertKeySendsWithinBound(RESEND_MS / 2);
    }

    @Test
    @Timeout(30)
    void slowAcks_ackAfterTwoResendPeriods_keySendsBoundedByKeysTimesResendsBeforeTheFirstAck() {
        assertKeySendsWithinBound(2 * RESEND_MS);
    }

    @Test
    @Timeout(30)
    void slowAcks_ackAfterThreeAndAHalfResendPeriods_keySendsBoundedByKeysTimesResendsBeforeTheFirstAck() {
        assertKeySendsWithinBound(7 * RESEND_MS / 2);
    }

    private static void assertKeySendsWithinBound(long ackDelayMs) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        IntStream.range(0, KEYS).forEach(i -> node.putLocal(key("slow-" + i), value("payload")).await());
        var resendsBeforeTheFirstAck = (ackDelayMs + RESEND_MS - 1) / RESEND_MS;
        var self = new AtomicReference<ScriptedNetwork>();
        var handle = new AtomicReference<DeparturePushRun>();
        var gate = new RoundGate((int) resendsBeforeTheFirstAck, self, handle);
        var network = ScriptedNetwork.scripted((_, sent) -> gate.onSend(sent));
        self.set(network);
        var run = direct(node, network, 3_000, RESEND_MS, Set::of, handle);
        var keySends = network.sends().stream().mapToLong(sent -> sent.response().entries().size()).sum();

        assertThat(network.sends().getFirst().response().entries()).as("one page of all keys first").hasSize(KEYS);
        assertThat(gate.replans()).as("a re-plan met the pages awaiting their acks").isPositive();
        assertThat(keySends).as("n * ceil(L / W), L = %d ms", ackDelayMs)
                            .isLessThanOrEqualTo(KEYS * resendsBeforeTheFirstAck);
        assertThat(run.report().owed()).isZero();
    }

    /// Delivers the ack of a round-r push once each of its keys was sent `resends` times (rounds r to r + resends - 1), and
    /// re-plans the run at the first push of each round. Both happen inside the push, outside the gate's lock; a push made during a
    /// re-plan never starts another one, so a run that pages again at a re-plan cannot recurse.
    private static final class RoundGate {
        private static final HexFormat HEX = HexFormat.of();

        private final int resends;
        private final AtomicReference<ScriptedNetwork> network;
        private final AtomicReference<DeparturePushRun> run;
        private final Map<String, Integer> sendsPerKey = new HashMap<>();
        private final Map<TimedSend, Integer> awaiting = new LinkedHashMap<>();
        private int lastReplannedRound = -1;
        private int replans;
        private boolean replanning;

        private RoundGate(int resends, AtomicReference<ScriptedNetwork> network, AtomicReference<DeparturePushRun> run) {
            this.resends = resends;
            this.network = network;
            this.run = run;
        }

        private record Step(boolean replan, List<TimedSend> released) {
            static Step step(boolean replan, List<TimedSend> released) {
                return new Step(replan, released);
            }
        }

        Reply onSend(TimedSend sent) {
            var step = record(sent);

            if (step.replan()) {
                run.get().onViewChange();
                endReplan();
            }
            step.released().forEach(push -> network.get().deliver(push, Reply.ACK));

            return Reply.DROP;
        }

        synchronized int replans() {
            return replans;
        }

        private synchronized Step record(TimedSend sent) {
            var keys = keysOf(sent);
            var round = keys.stream().mapToInt(key -> sendsPerKey.getOrDefault(key, 0)).max().orElse(0);

            keys.forEach(key -> sendsPerKey.merge(key, 1, Integer::sum));
            awaiting.put(sent, round);
            var replan = round > lastReplannedRound && !replanning;

            if (replan) {
                replanning = true;
                lastReplannedRound = round;
                replans++;
            }
            var released = new ArrayList<TimedSend>();

            awaiting.forEach((push, pushRound) -> releaseIfDue(push, pushRound, released));
            released.forEach(awaiting::remove);

            return Step.step(replan, List.copyOf(released));
        }

        private synchronized void endReplan() {
            replanning = false;
        }

        private void releaseIfDue(TimedSend push, int pushRound, List<TimedSend> released) {
            if (keysOf(push).stream().allMatch(key -> sendsPerKey.getOrDefault(key, 0) >= pushRound + resends)) {
                released.add(push);
            }
        }

        private static List<String> keysOf(TimedSend sent) {
            return sent.response().entries().stream().map(entry -> HEX.formatHex(entry.key())).toList();
        }
    }
}
