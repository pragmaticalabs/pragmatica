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
import org.pragmatica.dht.DHTRebalancerTest.EventRecorder;
import org.pragmatica.dht.DHTRebalancerTest.Reply;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3345: a resolved push releases what it registered. Its pages leave the shared ack registry (a late ack is then a no-op, as
/// [DHTRebalancer#onMigrationDataAck] documents), and the rebalancer forgets the run, so a later view change reaches only runs
/// still going.
class DepartureRunReleaseTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

    /// Every page is acked: the push closes clean and no page stays registered.
    @Test
    @Timeout(20)
    void ackedPush_resolves_noPageStaysRegistered() {
        assertNoPageRegisteredAfter(Reply.ACK, 0);
    }

    /// The target never answers: the push resolves at its deadline with the key owed, and no page stays registered.
    @Test
    @Timeout(20)
    void silentTarget_pushResolvesAtItsDeadline_noPageStaysRegistered() {
        assertNoPageRegisteredAfter(Reply.DROP, 1);
    }

    /// Through the rebalancer: once the push resolved, no run of it is active.
    @Test
    @Timeout(20)
    void resolvedPush_rebalancerKeepsNoActiveRun() throws ReflectiveOperationException {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "rr"), value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        var rebalancer = dhtRebalancer(node, network, RF1);
        network.ackVia(rebalancer::onMigrationDataAck);
        var tuning = new DeparturePushTuning(ms(100), ms(400), DeparturePushTuning.DEFAULT.reportAfter(), ms(200));
        var report = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(1_500).nanos()),
                                                tuning,
                                                Set::of,
                                                new EventRecorder(() -> {}))
                               .await()
                               .unwrap();
        var activeRuns = DHTRebalancer.class.getDeclaredField("activeRuns");
        activeRuns.setAccessible(true);

        assertThat(report.owed()).isZero();
        assertThat((Set<?>) activeRuns.get(rebalancer)).as("the resolved run is no longer active").isEmpty();
    }

    private static void assertNoPageRegisteredAfter(Reply reply, int owed) {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(soleKey(ring, "rp"), value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> reply);
        var registry = new ConcurrentHashMap<String, DeparturePushRun.Page>();
        var report = run(node, network, registry);

        assertThat(network.sends()).as("the key was paged").isNotEmpty();
        assertThat(report.owed()).isEqualTo(owed);
        assertThat(registry).as("pages registered by the resolved push").isEmpty();
    }

    /// As [DepartureDirectRuns#direct], over a registry the test keeps.
    private static DeparturePushReport run(DHTNode node,
                                           ScriptedNetwork network,
                                           ConcurrentHashMap<String, DeparturePushRun.Page> registry) {
        var tuning = new DeparturePushTuning(ms(100), ms(400), DeparturePushTuning.DEFAULT.reportAfter(), ms(200));
        var t0 = System.nanoTime();
        var window = DepartureWindow.departureWindow(t0 + ms(600).nanos() + tuning.eventDeliveryWait().nanos(), () -> false);
        var run = DeparturePushRun.departurePushRun(node, network, registry, 1, Set::of, t0, window, tuning, new EventRecorder(() -> {}));
        network.ackVia(ack -> Option.option(registry.get(ack.requestId())).onPresent(page -> page.onAck(ack)));
        return run.run().await().unwrap();
    }

    private static TimeSpan ms(long millis) {
        return timeSpan(millis).millis();
    }
}
