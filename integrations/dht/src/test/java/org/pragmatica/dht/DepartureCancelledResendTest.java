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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.DepartureLivePageTest.PROBE_DELAY_ROUNDS;
import static org.pragmatica.dht.DepartureLivePageTest.answerEarlierProbe;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345, the resend site: a single-key page a re-plan cancelled is never resent. The run is kept open past the cancelled page's
/// silence by the probe of the joiner, answered only after PROBE_DELAY_ROUNDS resend waits (event order), so a resend of the
/// cancelled page would happen before the close.
class DepartureCancelledResendTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(30)
    void cancelledSingleKeyPage_silentTarget_neverResent() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var key = soleKey(ring, "zs");
        var silent = delivererOf(ring, key, Set.of(LOCAL));
        var joiner = takeover(ring, key);
        node.putLocal(key, value("v")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var self = new AtomicReference<ScriptedNetwork>();
        var probeSends = new CopyOnWriteArrayList<TimedSend>();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? joinAtFirstSend(ring, joiner, handle)
                                                                : sent.target().equals(joiner) ? Reply.ACK : Reply.DROP);
        self.set(network);
        network.probesAnswer(probe -> answerEarlierProbe(probe, probeSends, self));
        var direct = direct(node, network, 1_500, 100, Set::of, handle);

        assertThat(network.sends().stream().filter(sent -> sent.target().equals(joiner))).as("control: J paged").isNotEmpty();
        assertThat(network.probes()).as("control: the run stayed open past the silence").hasSizeGreaterThan(PROBE_DELAY_ROUNDS);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(silent))).as("D's cancelled page never resent")
                                                                                          .hasSize(1);
        assertThat(direct.run().holderLosses()).isZero();
        assertThat(direct.report().owed()).isZero();
    }

    /// A probe whose target leaves the view while the probe is unanswered ends unanswered, not refused: the run records no lost
    /// holder for it (a silent probe is never a loss), and the key is still delivered to the next node.
    @Test
    @Timeout(30)
    void probeOfATargetThatLeaves_isUnansweredNotALostHolder() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var key = soleKey(ring, "zl");
        var leaving = delivererOf(ring, key, Set.of(LOCAL));
        node.putLocal(key, value("v")).await();
        var handle = new AtomicReference<DeparturePushRun>();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var probes = new AtomicInteger();
        network.probesAnswer(probe -> leaveDuringFirstProbe(probes, ring, leaving, handle));
        var direct = direct(node, network, 1_500, 100, Set::of, handle);

        assertThat(network.probes()).as("control: the first probe was sent").isNotEmpty();
        assertThat(ring.nodes()).as("control: the target left").doesNotContain(leaving);
        assertThat(direct.run().holderLosses()).isZero();
        assertThat(direct.report().owed()).isZero();
    }

    private static Reply leaveDuringFirstProbe(AtomicInteger probes,
                                               ConsistentHashRing<NodeId> ring,
                                               NodeId leaving,
                                               AtomicReference<DeparturePushRun> handle) {
        if (probes.getAndIncrement() == 0) {
            ring.removeNode(leaving);
            handle.get().onViewChange();
            return Reply.DROP;
        }
        return Reply.ACK;
    }

    private static Reply joinAtFirstSend(ConsistentHashRing<NodeId> ring, NodeId joiner, AtomicReference<DeparturePushRun> handle) {
        ring.addNode(joiner);
        handle.get().onViewChange();

        return Reply.DROP;
    }
}
