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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: a failed pre-exit probe is ONE holder loss. The probe's own failing answer (a "draining" nack or a token mismatch) is not
/// a loss by itself: the probe round reports it once, as "probe failed".
class DepartureProbeLossTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    /// The key is acked; the first probe is answered by a "draining" nack, every later one by an ack. One loss, the key re-paged
    /// and acked, nothing owed.
    @Test
    @Timeout(20)
    void drainingNackToTheFirstProbe_countsOneHolderLoss() {
        assertOneLossWhenTheFirstProbeAnswers(Reply.DRAINING_NACK);
    }

    /// As above, the first probe answered by an ack carrying another boot token.
    @Test
    @Timeout(20)
    void tokenMismatchToTheFirstProbe_countsOneHolderLoss() {
        assertOneLossWhenTheFirstProbeAnswers(Reply.ackWithToken(7L));
    }

    /// A probe answered by a nack (not "draining", the holder's token) passes: the holder answered in its tenure. One probe round,
    /// no loss, nothing owed.
    @Test
    @Timeout(20)
    void nackToEveryProbe_probePasses_noHolderLoss() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "pn"), value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        network.probesAnswer(_ -> Reply.NACK);
        var direct = direct(node, network, 1_500, 100, Set::of);

        assertThat(network.probes()).as("one probe round").hasSize(1);
        assertThat(direct.run().holderLosses()).isZero();
        assertThat(direct.report().owed()).isZero();
    }

    private static void assertOneLossWhenTheFirstProbeAnswers(Reply firstProbeAnswer) {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "pl"), value("payload")).await();
        var probes = new AtomicInteger();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        network.probesAnswer(_ -> probes.getAndIncrement() == 0
                                  ? firstProbeAnswer
                                  : Reply.ACK);
        var direct = direct(node, network, 1_500, 100, Set::of);

        assertThat(network.probes()).as("the failed probe and the one after the re-page").hasSizeGreaterThanOrEqualTo(2);
        assertThat(direct.run().holderLosses()).as("one loss per failed probe").isEqualTo(1);
        assertThat(direct.report().owed()).isZero();
    }
}
