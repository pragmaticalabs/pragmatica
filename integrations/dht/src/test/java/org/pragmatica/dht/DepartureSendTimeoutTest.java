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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345: every wait of a departure push run is bounded by its deadline, the network send included (`DHTNetwork.send` promises
/// no bound of its own). A send that never resolves is a dead connection once its deadline passes, never a wait without end.
class DepartureSendTimeoutTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long WINDOW_MS = 1_500;
    private static final long RESEND_MS = 100;

    /// The key's page is acked at once, so the exit probes its holder; every probe send hangs. The exit probe and then the deadline
    /// probe round each end at their own deadline, and the run closes: the key stays owed (no probe passed) and the run resolves
    /// within a few `resendAfter` of the window, never waits on the send.
    @Test
    @Timeout(20)
    void probeSendNeverResolves_runStillClosesAtItsDeadline() {
        var ring = fiveNodeRing();
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        var k = soleKey(ring, "st");
        node.putLocal(k, value("payload")).await();
        var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
        network.probeSendsHang();
        var started = System.nanoTime();
        var direct = direct(node, network, WINDOW_MS, RESEND_MS, Set::of);
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(network.ackedKeys()).as("control: the page was acked").hasSize(1);
        assertThat(network.probes()).as("control: the exit and the deadline probe round both sent a probe").hasSizeGreaterThanOrEqualTo(2);
        assertThat(direct.report().owed()).as("no probe passed: the acked key is not confirmed").isEqualTo(1);
        assertThat(elapsedMs).as("the run closed near its deadline").isLessThan(WINDOW_MS + 10 * RESEND_MS);
    }
}
