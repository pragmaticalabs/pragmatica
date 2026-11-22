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
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: a failed pre-exit probe is ONE holder loss. The probe's own failing answer (a "draining" nack or a token mismatch) is not
/// a loss by itself: the probe round reports it once, as "probe failed". A "draining" answer is terminal for the target within the
/// run, whatever the page type (ac517fb077): the target leaves the owed set and is never paged again.
class DepartureProbeLossTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    /// The key is acked; the first probe, to the target, is answered by a "draining" nack and the target leaves the drain set. One loss, the target terminal: never paged again, not owed; the next deliverer is paged and the key is delivered.
    @Test
    @Timeout(20)
    void drainingNackToTheFirstProbe_countsOneHolderLoss() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var key = soleKey(ring, "pd");
        node.putLocal(key, value("payload")).await();
        var target = delivererOf(ring, key, Set.of(LOCAL));
        var next = delivererOf(ring, key, Set.of(LOCAL, target));
        var drainSet = new AtomicReference<Set<NodeId>>(Set.of());
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        network.probesAnswer(sent -> drainingProbe(target, sent, drainSet));
        var direct = direct(node, network, 1_500, 100, drainSet::get);
        var holderLosses = direct.run().holderLosses();

        assertThat(holderLosses).as("one loss for the draining probe").isEqualTo(1);
        assertThat(network.sends().stream().filter(sent -> sent.target().equals(target))).as("data sends to the draining target: no re-page")
                                                                                         .hasSize(1);
        assertThat(network.probes().stream().filter(sent -> sent.target().equals(target))).as("probes to the draining target").hasSize(1);
        assertThat(network.sends()).as("the next deliverer is paged").anyMatch(sent -> sent.target().equals(next));
        assertThat(direct.report().owed()).as("owed excludes the draining target, the next deliverer holds the key").isZero();
    }

    private static Reply drainingProbe(NodeId target, TimedSend sent, AtomicReference<Set<NodeId>> drainSet) {
        if (sent.target().equals(target)) {
            drainSet.set(Set.of(target));
            return Reply.DRAINING_NACK;
        }
        return Reply.ACK;
    }

    /// The key is acked, the probe answered "draining", and any later data page to the same node would be answered "draining" too.
    /// One loss, and no second data page: the probe's refusal already made the target terminal.
    @Test
    @Timeout(20)
    void probeThenDataDraining_sameNode_countsOneLoss() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "pt"), value("payload")).await();
        var network = ScriptedNetwork.scripted((index, _) -> index == 0
                                                             ? Reply.ACK
                                                             : Reply.DRAINING_NACK);
        network.probesAnswer(_ -> Reply.DRAINING_NACK);
        var direct = direct(node, network, 1_500, 100, Set::of);

        assertThat(direct.run().holderLosses()).as("probe draining then data draining, same node: one loss").isEqualTo(1);
        assertThat(network.sends()).as("sends to the draining node: the first data page only").hasSize(1);
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
