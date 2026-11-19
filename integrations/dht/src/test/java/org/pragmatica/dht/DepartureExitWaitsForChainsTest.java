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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 trigger 3: an exit is attempted only when no chain holds a live page (`live(page)`: registered, not cancelled,
/// current). A chain whose page is dead (its target left the view) still runs until its attempt times out, but it no longer holds
/// the exit: the probe goes out without waiting for it.
class DepartureExitWaitsForChainsTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long RESEND_MS = 600;

    /// The key's deliverer leaves the ring at its send, silent; the re-plan pages the next deliverer, which acks at once, so nothing
    /// is owed. The first chain still waits for its silent attempt, but its page is dead: the probe goes out well before that wait
    /// (`resendAfter`) ends.
    @Test
    @Timeout(20)
    void silentChainOfALeftTarget_deadPageDoesNotHoldTheExit() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "ew");
        var first = delivererOf(ring, k, Set.of(LOCAL));
        node.putLocal(k, value("payload")).await();
        var network = ScriptedNetwork.scriptedOver(ring, (_, sent) -> leaveSilentlyOrAck(ring, first, sent.target()));
        var direct = direct(node, network, 3_000, RESEND_MS, Set::of);

        assertThat(network.targets()).as("the first deliverer, then the next").hasSize(2).startsWith(first);
        assertThat(direct.report().owed()).isZero();
        var probeAfterMs = (network.probes().getFirst().atNanos() - network.sends().getFirst().atNanos()) / 1_000_000;
        assertThat(probeAfterMs).as("the probe did not wait for the dead page's chain").isLessThan(RESEND_MS / 2);
    }

    private static Reply leaveSilentlyOrAck(ConsistentHashRing<NodeId> ring, NodeId first, NodeId target) {
        if (target.equals(first)) {
            ring.removeNode(first);
            return Reply.DROP;
        }
        return Reply.ACK;
    }
}
