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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancerTest.LOCAL;
import static org.pragmatica.dht.DHTRebalancerTest.PEER_A;
import static org.pragmatica.dht.DHTRebalancerTest.key;
import static org.pragmatica.dht.DHTRebalancerTest.value;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #3345 P3: a pre-exit probe answers within `resendAfter` AND before the attempts deadline, or it fails. With `resendAfter`
/// (1000 ms) longer than what is left of the window when the probe goes out, the probe's wait ends at the deadline; an answer
/// landing after the deadline (but within `resendAfter` of the probe) covers nothing: the close (after the deadline's own probe
/// round, here unanswered) still owes the key.
class DepartureProbeDeadlineTest {
    private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
    private static final long WINDOW_MS = 600;
    private static final long RESEND_MS = 1_000;
    private static final long DATA_ACK_MS = 400;
    private static final long PROBE_ANSWER_MS = 300;

    @Test
    @Timeout(20)
    void preExitProbe_answeredAfterTheAttemptsDeadline_coversNothing() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        List.of(LOCAL, PEER_A).forEach(ring::addNode);
        var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
        node.putLocal(key("late-probe"), value("payload")).await();
        var self = new AtomicReference<ScriptedNetwork>();
        var lateAnswerSent = new AtomicBoolean();
        var network = ScriptedNetwork.scripted((index, sent) -> index == 0
                                                                ? answerLater(self, sent, DATA_ACK_MS)
                                                                : Reply.DROP);
        self.set(network);
        network.probesAnswer(probe -> lateAnswerSent.getAndSet(true)
                                      ? Reply.DROP
                                      : answerLater(self, probe, PROBE_ANSWER_MS));
        var run = direct(node, network, WINDOW_MS, RESEND_MS, Set::of);

        assertThat(lateAnswerSent).as("the pre-exit probe went out").isTrue();
        assertThat(run.report().owed()).as("a probe answer after the attempts deadline covers nothing").isEqualTo(1);
    }

    /// `sent` is acked `delayMs` later, from another thread.
    private static Reply answerLater(AtomicReference<ScriptedNetwork> self, TimedSend sent, long delayMs) {
        CompletableFuture.runAsync(() -> self.get().deliver(sent, Reply.ACK),
                                   CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
        return Reply.DROP;
    }
}
