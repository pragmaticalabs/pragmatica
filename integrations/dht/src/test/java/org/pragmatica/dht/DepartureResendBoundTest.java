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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
/// safe is never sent again (the page is settled, a resend carries only open entries, a split keeps only open keys). With n keys in
/// one page and every ack delayed by L, each key is sent once per W elapsed before its first ack lands: n * ceil(L / W) key-sends
/// (measured: 64, 128 and 256 for L = W/2, 2W and 3.5W). Before the rule, n = 64 and L = 2W cost 524 key-sends.
///
/// The skip rule sits at three sites (`isSettled`, the open entries of a resend, the open keys of a split). Each site alone, and
/// the open-entries and split sites together, are redundant with the others (measured: removing any of them leaves the key-sends
/// unchanged); only removing all three together breaks the bound (L = 2W: 203 key-sends), which these tests catch.
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
        var self = new AtomicReference<ScriptedNetwork>();
        var network = ScriptedNetwork.scripted((_, sent) -> ackLater(self, sent, ackDelayMs));
        self.set(network);
        var run = direct(node, network, 3_000, RESEND_MS, Set::of);
        var resendsBeforeTheFirstAck = (ackDelayMs + RESEND_MS - 1) / RESEND_MS;
        var keySends = network.sends().stream().mapToLong(sent -> sent.response().entries().size()).sum();

        assertThat(network.sends().getFirst().response().entries()).as("one page of all keys first").hasSize(KEYS);
        assertThat(keySends).as("n * ceil(L / W), L = %d ms", ackDelayMs)
                            .isLessThanOrEqualTo(KEYS * resendsBeforeTheFirstAck);
        assertThat(run.report().owed()).isZero();
    }

    /// Every push is acked `ackDelayMs` later, from another thread.
    private static Reply ackLater(AtomicReference<ScriptedNetwork> self, TimedSend sent, long ackDelayMs) {
        CompletableFuture.runAsync(() -> self.get().deliver(sent, Reply.ACK),
                                   CompletableFuture.delayedExecutor(ackDelayMs, TimeUnit.MILLISECONDS));
        return Reply.DROP;
    }
}
