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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: an ack that does not count (IGNORED: here its target was out of the ring when it landed) does not end the attempt. The
/// page still waits `resendAfter` for an answer that counts and is resent when none comes; the resend's ack makes the key safe.
/// The run never observes a view change, so the page stays current and only the resend can deliver the key.
class DepartureIgnoredAckTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(20)
    void ignoredAck_targetOutOfTheRingWhenItLands_pageIsResentAndTheKeyDelivered() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key("ignored-ack"), value("payload")).await();
        var self = new AtomicReference<ScriptedNetwork>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? ackWhileOutOfTheRing(self, ring, sent)
                                                                : Reply.ACK);
        self.set(network);
        var run = direct(node, network, 1_500, 100, Set::of);

        assertThat(network.sends()).as("the ignored ack left the page to its resend").hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.report().owed()).as("the resend's ack delivered the key").isZero();
    }

    /// The target leaves the ring, the ack lands 20 ms later (ignored: the target is out of the ring), then the target is back.
    private static Reply ackWhileOutOfTheRing(AtomicReference<ScriptedNetwork> self, ConsistentHashRing<NodeId> ring, TimedSend sent) {
        ring.removeNode(sent.target());
        CompletableFuture.runAsync(() -> ackThenRejoin(self.get(), ring, sent),
                                   CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
        return Reply.DROP;
    }

    private static void ackThenRejoin(ScriptedNetwork network, ConsistentHashRing<NodeId> ring, TimedSend sent) {
        network.deliver(sent, Reply.ACK);
        ring.addNode(sent.target());
    }
}
