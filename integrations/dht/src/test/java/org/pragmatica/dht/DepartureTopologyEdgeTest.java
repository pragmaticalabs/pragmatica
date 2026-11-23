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
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.DHTRebalancerTest.EventRecorder;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.ringPlus;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3345: every ring edge of [DHTTopologyListener] (a join, a recovery out of DEPARTING, a removal, a departing edge) re-plans a running departure
/// push. The push runs through a real [DHTRebalancer] whose only view-change source is the listener; its key's first deliverer
/// never answers, and the edge, raised at that first send, gives the key a deliverer that acks. Without the re-plan the key is
/// still owed at the deadline.
class DepartureTopologyEdgeTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    @Test
    @Timeout(20)
    void onNodeJoined_duringADeparturePush_rePlansTheKeyToTheJoiner() {
        var ring = fiveNodeRing();
        var key = soleKey(ring, "edge-join");
        var joiner = takeover(ring, key);

        assertRePlannedTo(ring, key, joiner, listener -> listener.onNodeJoined(nodeJoined(joiner)));
    }

    @Test
    @Timeout(20)
    void onNodeRecovered_duringADeparturePush_rePlansTheKeyToTheRecoveredNode() {
        var ring = fiveNodeRing();
        var key = soleKey(ring, "edge-recover");
        var recovered = takeover(ring, key);

        assertRePlannedTo(ringPlus(ring, recovered),
                          key,
                          recovered,
                          listener -> listener.onNodeDeparting(recovered),
                          listener -> listener.onNodeRecovered(recovered));
    }

    @Test
    @Timeout(20)
    void onNodeRemoved_duringADeparturePush_rePlansTheKeyToTheNextNode() {
        var ring = fiveNodeRing();
        var key = soleKey(ring, "edge-remove");
        var first = delivererOf(ring, key, Set.of(LOCAL));
        var next = delivererOf(ring, key, Set.of(LOCAL, first));

        assertRePlannedTo(ring, key, next, listener -> listener.onNodeRemoved(nodeRemoved(first)));
    }

    /// The departing edge reaches the rebalancer through the rebalance step alone (#3415 prunes with a recorded ring change, not
    /// through the removal path), so the step carries the re-plan trigger: a drain of the first deliverer re-plans the key.
    @Test
    @Timeout(20)
    void onNodeDeparting_duringADeparturePush_rePlansTheKeyToTheNextNode() {
        var ring = fiveNodeRing();
        var key = soleKey(ring, "edge-depart");
        var first = delivererOf(ring, key, Set.of(LOCAL));
        var next = delivererOf(ring, key, Set.of(LOCAL, first));

        assertRePlannedTo(ring, key, next, listener -> listener.onNodeDeparting(first));
    }

    /// The first deliverer of `key` is silent; at its first send `edge` runs (on another thread, as membership does), after which
    /// `expected` is the key's deliverer and acks.
    private static void assertRePlannedTo(ConsistentHashRing<NodeId> ring,
                                          byte[] key,
                                          NodeId expected,
                                          Consumer<DHTTopologyListener> edge) {
        assertRePlannedTo(ring, key, expected, _ -> {}, edge);
    }

    /// As above, with `before` run on the listener ahead of the push: a recovery restores only a node this listener pruned on its
    /// departing edge (#3415), so the recovery case prunes `expected` first and the edge then recovers it.
    private static void assertRePlannedTo(ConsistentHashRing<NodeId> ring,
                                          byte[] key,
                                          NodeId expected,
                                          Consumer<DHTTopologyListener> before,
                                          Consumer<DHTTopologyListener> edge) {
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key, value("payload")).await();
        var listener = new AtomicReference<DHTTopologyListener>();
        var raised = new AtomicBoolean();
        var network = ScriptedNetwork.scripted((_, sent) -> sent.target().equals(expected)
                                                            ? Reply.ACK
                                                            : raiseOnce(raised, () -> edge.accept(listener.get())));
        var rebalancer = dhtRebalancer(node, network, RF1);
        listener.set(DHTTopologyListener.dhtTopologyListener(node, rebalancer));
        before.accept(listener.get());
        network.ackVia(rebalancer::onMigrationDataAck);
        var window = DepartureWindow.departureWindow(System.nanoTime() + ms(2_000).nanos());
        var tuning = new DeparturePushTuning(ms(100), ms(400), DeparturePushTuning.DEFAULT.reportAfter(), ms(200));
        var report = rebalancer.pushOnDeparture(window, tuning, Set::of, new EventRecorder(() -> {})).await().unwrap();

        assertThat(raised).as("the edge was raised").isTrue();
        assertThat(network.targets()).as("the edge re-planned the key to %s", expected).contains(expected);
        assertThat(report.owed()).as("the re-planned key is delivered, not owed at the deadline").isZero();
    }

    private static Reply raiseOnce(AtomicBoolean raised, Runnable edge) {
        if (!raised.getAndSet(true)) {
            Thread.ofVirtual().start(edge);
        }
        return Reply.DROP;
    }

    private static MembershipDecision.NodeJoined nodeJoined(NodeId nodeId) {
        return new MembershipDecision.NodeJoined(nodeId, List.of(), 1L, HlcTimestamp.ZERO);
    }

    private static MembershipDecision.NodeRemoved nodeRemoved(NodeId nodeId) {
        return new MembershipDecision.NodeRemoved(nodeId, List.of(), 1L, HlcTimestamp.ZERO);
    }

    private static TimeSpan ms(long millis) {
        return timeSpan(millis).millis();
    }
}
