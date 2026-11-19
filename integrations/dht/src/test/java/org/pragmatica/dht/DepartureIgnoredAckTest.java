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

/// #3345: an ack that does not count (IGNORED: its page is no longer current, because its target left V at an observed re-plan and
/// came back as a new tenure) does not end the attempt and is never a holder. The returned target is paged again in its new tenure,
/// and that page's ack makes the key safe. May-act and the ack read V, never the live ring: a ring change nobody observed leaves
/// the page current and its ack counted (DepartureLivePageTest).
class DepartureIgnoredAckTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(20)
    void ignoredAck_targetLeftTheViewAndCameBack_newTenurePageDeliversTheKey() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key("ignored-ack"), value("payload")).await();
        var self = new AtomicReference<ScriptedNetwork>();
        var handle = new AtomicReference<DeparturePushRun>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? ackWhileOutOfTheView(self, ring, handle, sent)
                                                                : Reply.ACK);
        self.set(network);
        var run = direct(node, network, 1_500, 100, Set::of, handle);

        assertThat(network.sends()).as("the ignored ack left the key to a page of the new tenure").hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.report().owed()).as("the new tenure's ack delivered the key").isZero();
    }

    /// The target leaves the ring and the run observes it, the ack lands 20 ms later (ignored: its page is dead), then the target is
    /// back and the run observes that too.
    private static Reply ackWhileOutOfTheView(AtomicReference<ScriptedNetwork> self,
                                              ConsistentHashRing<NodeId> ring,
                                              AtomicReference<DeparturePushRun> handle,
                                              TimedSend sent) {
        ring.removeNode(sent.target());
        handle.get().onViewChange();
        CompletableFuture.runAsync(() -> ackThenRejoin(self.get(), ring, handle.get(), sent),
                                   CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
        return Reply.DROP;
    }

    private static void ackThenRejoin(ScriptedNetwork network, ConsistentHashRing<NodeId> ring, DeparturePushRun run, TimedSend sent) {
        network.deliver(sent, Reply.ACK);
        ring.addNode(sent.target());
        run.onViewChange();
    }
}
