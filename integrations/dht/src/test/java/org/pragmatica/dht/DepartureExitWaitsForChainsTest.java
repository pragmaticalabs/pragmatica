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
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 trigger 3: an exit is attempted only when no chain holds a live page (`mayAct(page)`: registered, not cancelled,
/// current). A chain whose page is dead (its target left the view, or a re-plan cancelled it) still runs until its attempt times
/// out, but it no longer holds the exit: the probe goes out without waiting for it. A page is finished before its chain's result
/// completes, so the exit trigger at the chain's end sees it.
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

    /// The count of live chains asks `mayAct`, not `current`: a joiner takes the key over inside the first send, so the re-plan
    /// cancels that page while its target STAYS in the view (the page is still current). The joiner acks at once; the cancelled
    /// page's silent attempt must not hold the exit, so the probe goes out well before that wait (`resendAfter`) ends.
    @Test
    @Timeout(20)
    void silentCancelledPageOfAStayingTarget_doesNotHoldTheExit() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "ec");
        var first = delivererOf(ring, k, Set.of(LOCAL));
        var joiner = takeover(ring, k);
        node.putLocal(k, value("payload")).await();
        var network = ScriptedNetwork.scriptedOver(ring, (index, _) -> joinSilentlyOrAck(ring, joiner, index));
        var direct = direct(node, network, 3_000, RESEND_MS, Set::of);

        assertThat(network.targets()).as("the first deliverer, then the joiner").hasSize(2).startsWith(first).endsWith(joiner);
        assertThat(ring.nodes()).as("control: the first deliverer stayed in the view").contains(first);
        assertThat(direct.report().owed()).isZero();
        var probeAfterMs = (network.probes().getFirst().atNanos() - network.sends().getFirst().atNanos()) / 1_000_000;
        assertThat(probeAfterMs).as("the probe did not wait for the cancelled page's chain").isLessThan(RESEND_MS / 2);
    }

    /// The exit trigger is the end of a chain, and a page is finished BEFORE that chain's result completes. The ack comes later,
    /// from another thread, so the chain's last step completes asynchronously: if the page were marked finished by a side callback
    /// of the same promise, the trigger could see it still running, start no exit, and leave the run to its deadline.
    @Test
    @Timeout(20)
    void exitTrigger_pageFinishedBeforeTheChainResult_exitsBeforeTheDeadline() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "ef");
        node.putLocal(k, value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ASYNC_ACK);
        var started = System.nanoTime();
        var direct = direct(node, network, 3_000, RESEND_MS, Set::of);
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(network.sends()).as("control: one page, acked later").hasSize(1);
        assertThat(direct.run().exitAttempts()).as("the chain's end started an exit").isPositive();
        assertThat(direct.report().owed()).isZero();
        assertThat(elapsedMs).as("the run exits at once, not at the deadline").isLessThan(1_500);
    }

    private static Reply joinSilentlyOrAck(ConsistentHashRing<NodeId> ring, NodeId joiner, int index) {
        if (index == 0) {
            ring.addNode(joiner);
            return Reply.DROP;
        }
        return Reply.ACK;
    }

    private static Reply leaveSilentlyOrAck(ConsistentHashRing<NodeId> ring, NodeId first, NodeId target) {
        if (target.equals(first)) {
            ring.removeNode(first);
            return Reply.DROP;
        }
        return Reply.ACK;
    }
}
