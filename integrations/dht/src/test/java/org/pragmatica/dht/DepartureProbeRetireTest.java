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
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: a probe round retires its probes when it ends. A late answer to a retired probe is no answer at all: it never records a
/// holder, so it cannot stand in for the holder's current process.
class DepartureProbeRetireTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long RESTARTED = 5L;

    /// The target acks the key (token 0); the first probe is silent and fails: one holder loss. The target restarted (token 5): when
    /// the key is paged again, the first probe's answer arrives late, with the old token, just before the re-paged key's ack. The
    /// retired probe's answer records nothing, so the new ack makes the holder (token 5) and no second loss follows.
    @Test
    @Timeout(20)
    void lateAnswerToAFailedProbe_isIgnored_noSecondHolderLoss() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "pr"), value("payload")).await();
        var probes = new AtomicInteger();
        var self = new AtomicReference<ScriptedNetwork>();
        var network = ScriptedNetwork.scripted((index, _) -> index == 0
                                                             ? Reply.ACK
                                                             : lateProbeAnswerThenRestartedAck(self.get()));
        self.set(network);
        network.probesAnswer(_ -> probes.getAndIncrement() == 0
                                  ? Reply.DROP
                                  : Reply.ackWithToken(RESTARTED));
        var direct = direct(node, network, 1_500, 100, Set::of);

        assertThat(network.probes()).as("the failed probe and the one after the re-page").hasSizeGreaterThanOrEqualTo(2);
        assertThat(direct.run().holderLosses()).as("only the failed probe").isEqualTo(1);
        assertThat(direct.report().owed()).isZero();
    }

    /// The first probe's late answer (old token 0), then the ack of the restarted process.
    private static Reply lateProbeAnswerThenRestartedAck(ScriptedNetwork network) {
        network.deliver(network.probes().getFirst(), Reply.ACK);
        return Reply.ackWithToken(RESTARTED);
    }
}
