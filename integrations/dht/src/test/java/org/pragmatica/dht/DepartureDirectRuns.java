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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTRebalancerTest.EventRecorder;
import org.pragmatica.dht.DHTRebalancerTest.ScriptedNetwork;
import org.pragmatica.dht.DHTRebalancerTest.TimedSend;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_B;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_C;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_D;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The direct-run fixture of the departure-holder tests (#3345): a [DeparturePushRun] driven without a [DHTRebalancer], its acks
/// routed by correlation id as the rebalancer does, over a [ScriptedNetwork]; plus the ring helpers those tests share.
final class DepartureDirectRuns {
    private DepartureDirectRuns() {}

    record Direct(DeparturePushRun run, DeparturePushReport report) {}

    static Direct direct(DHTNode node, ScriptedNetwork network, long windowMs, long resendMs, Supplier<Set<NodeId>> drainSet) {
        return direct(node, network, windowMs, resendMs, drainSet, new AtomicReference<>());
    }

    /// A run over RF 1 with a `windowMs` attempts window; acks are routed by correlation id as [DHTRebalancer] does.
    static Direct direct(DHTNode node,
                         ScriptedNetwork network,
                         long windowMs,
                         long resendMs,
                         Supplier<Set<NodeId>> drainSet,
                         AtomicReference<DeparturePushRun> handle) {
        return direct(node, network, windowMs, resendMs, drainSet, handle, () -> false);
    }

    /// As above, with the node's halt flag `halted`.
    static Direct direct(DHTNode node,
                         ScriptedNetwork network,
                         long windowMs,
                         long resendMs,
                         Supplier<Set<NodeId>> drainSet,
                         AtomicReference<DeparturePushRun> handle,
                         BooleanSupplier halted) {
        var registry = new ConcurrentHashMap<String, DeparturePushRun.Page>();
        var tuning = new DeparturePushTuning(ms(resendMs), ms(4 * resendMs), DeparturePushTuning.DEFAULT.reportAfter(), ms(200));
        var t0 = System.nanoTime();
        var window = DepartureWindow.departureWindow(t0 + ms(windowMs).nanos() + tuning.eventDeliveryWait().nanos(), halted);
        var run = DeparturePushRun.departurePushRun(node, network, registry, 1, drainSet, t0, window, tuning, new EventRecorder(() -> {}));
        handle.set(run);
        network.ackVia(ack -> Option.option(registry.get(ack.requestId())).onPresent(page -> page.onAck(ack)));
        network.viewChangeVia(run::onViewChange);
        return new Direct(run, run.run().await().unwrap());
    }

    static boolean sameRequest(TimedSend sent, TimedSend other) {
        return other != null && sent.response().requestId().equals(other.response().requestId());
    }

    static NodeId delivererOf(ConsistentHashRing<NodeId> ring, byte[] key, Set<NodeId> leaving) {
        return ring.nodesFor(key, 1, candidate -> !leaving.contains(candidate)).getFirst();
    }

    /// A node that, once added to `ring`, becomes `key`'s deliverer.
    static NodeId takeover(ConsistentHashRing<NodeId> ring, byte[] key) {
        return IntStream.range(0, 20_000)
                        .mapToObj(i -> new NodeId("joiner-" + i))
                        .filter(candidate -> delivererOf(ringPlus(ring, candidate), key, Set.of(LOCAL)).equals(candidate))
                        .findFirst()
                        .orElseThrow();
    }

    /// A node that, once added to `ring`, leaves `key`'s deliverer unchanged.
    static NodeId bystander(ConsistentHashRing<NodeId> ring, byte[] key) {
        var deliverer = delivererOf(ring, key, Set.of(LOCAL));
        return IntStream.range(0, 20_000)
                        .mapToObj(i -> new NodeId("bystander-" + i))
                        .filter(candidate -> delivererOf(ringPlus(ring, candidate), key, Set.of(LOCAL)).equals(deliverer))
                        .findFirst()
                        .orElseThrow();
    }

    private static ConsistentHashRing<NodeId> ringPlus(ConsistentHashRing<NodeId> ring, NodeId extra) {
        var copy = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.nodes().forEach(copy::addNode);
        copy.addNode(extra);
        return copy;
    }

    /// A second RF-1 key of LOCAL with the same deliverer as `key`.
    static byte[] sameDeliverer(ConsistentHashRing<NodeId> ring, byte[] key, String prefix) {
        var deliverer = delivererOf(ring, key, Set.of(LOCAL));
        for (int i = 0; i < 20_000; i++) {
            var candidate = soleKey(ring, prefix + "-" + i);
            if (delivererOf(ring, candidate, Set.of(LOCAL)).equals(deliverer)) {
                return candidate;
            }
        }
        throw new AssertionError("no second key delivered to " + deliverer);
    }

    static ConsistentHashRing<NodeId> fiveNodeRing() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A, PEER_B, PEER_C, PEER_D).forEach(ring::addNode);
        return ring;
    }

    /// A key LOCAL alone is responsible for at RF 1.
    static byte[] soleKey(ConsistentHashRing<NodeId> ring, String prefix) {
        for (int i = 0; i < 20_000; i++) {
            var candidate = key(prefix + "-" + i);
            if (ring.nodesFor(candidate, 1).equals(List.of(LOCAL))) {
                return candidate;
            }
        }
        throw new AssertionError("no RF-1 key held by LOCAL");
    }

    /// Whether `sent` carries `key`.
    static boolean carriesKey(TimedSend sent, byte[] key) {
        return sent.response().entries().stream().anyMatch(entry -> Arrays.equals(entry.key(), key));
    }

    /// A key LOCAL alone holds whose deliverer is not `avoid`.
    static byte[] otherTargetKey(ConsistentHashRing<NodeId> ring, NodeId avoid, String prefix) {
        for (int i = 0; i < 200; i++) {
            var candidate = soleKey(ring, prefix + "-" + i);
            if (!delivererOf(ring, candidate, Set.of(LOCAL)).equals(avoid)) {
                return candidate;
            }
        }
        throw new AssertionError("no key with a deliverer other than " + avoid);
    }

    private static TimeSpan ms(long millis) {
        return timeSpan(millis).millis();
    }
}
