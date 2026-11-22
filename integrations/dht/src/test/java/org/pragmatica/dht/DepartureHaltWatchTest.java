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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 P2: the halt watch reads the node's halt flag once per `resendAfter` (floored at 1 ms), not faster. Over a window of
/// ten resend intervals on a silent target, the flag is read by the watch about ten times and once per send (the key's page is
/// resent every interval): a few dozen reads, where a watch at the 1 ms floor would read it hundreds of times.
class DepartureHaltWatchTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long WINDOW_MS = 1_000;
    private static final long RESEND_MS = 100;
    private static final int READ_BOUND = 100;

    @Test
    @Timeout(20)
    void haltWatch_silentTarget_readsTheHaltFlagOncePerResendIntervalNotFaster() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key("halt-watch"), value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
        var reads = new AtomicInteger();
        var run = direct(node, network, WINDOW_MS, RESEND_MS, Set::of, new AtomicReference<>(), () -> reads.incrementAndGet() < 0);

        assertThat(run.report().owed()).as("the silent target never took the key").isEqualTo(1);
        assertThat(reads).as("halt-flag reads over %d ms at a %d ms watch period", WINDOW_MS, RESEND_MS)
                         .hasValueGreaterThan(0)
                         .hasValueLessThan(READ_BOUND);
    }
}
