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
import org.pragmatica.dht.DHTRebalancerTest.EventRecorder;
import org.pragmatica.dht.DHTRebalancerTest.Incomplete;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2034, #3345: AT_RISK is raised once per push. A sole key with no deliverer raises it at plan time; still owed when the push
/// resolves at its deadline, it is not raised a second time there: the close reports it as incomplete only.
class DepartureAtRiskOnceTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(20)
    void soleKeyWithoutADeliverer_atRiskAtPlanTime_notRaisedAgainAtTheDeadline() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(LOCAL);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key("ar"), value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var rebalancer = dhtRebalancer(node, network, RF1);
        network.ackVia(rebalancer::onMigrationDataAck);
        var recorder = new EventRecorder(() -> {});
        var tuning = new DeparturePushTuning(ms(50), ms(200), DeparturePushTuning.DEFAULT.reportAfter(), ms(200));
        var report = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(400).nanos()),
                                                tuning,
                                                Set::of,
                                                recorder)
                               .await()
                               .unwrap();

        assertThat(network.sends()).as("no deliverer, nothing paged").isEmpty();
        assertThat(recorder.atRisk()).as("raised once, at plan time").containsExactly(1);
        assertThat(recorder.delivered()).isEmpty();
        assertThat(recorder.incomplete()).containsExactly(new Incomplete(1, 1));
        assertThat(report.soleCopyAtRisk()).isEqualTo(1);
    }

    private static TimeSpan ms(long millis) {
        return timeSpan(millis).millis();
    }
}
