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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A replica that leaves the ring while it still owes a reply is counted in the all-miss report and makes the
/// verdict `unreachable`: its "absent" was never heard. Driven by hand through a capturing network, as in
/// `DistributedDHTClientTest.DepartureReissue`.
class DHTDepartureAttributionTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(10).seconds());

    private record Sent(NodeId target, ProtocolMessage message) {}

    @Test
    void allMiss_afterAReplicaDepartsMidRead_reportsDepartedAndUnreachable() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        for (var i = 1; i <= 8; i++) {
            ring.addNode(new NodeId("replica-" + i));
        }
        var sent = new CopyOnWriteArrayList<Sent>();
        DHTNetwork network = (target, message) -> sent.add(new Sent(target, message));
        var miss = new AtomicReference<ResolveMiss>();
        ResolveFallbackObserver observer = new ResolveFallbackObserver() {
            @Override
            public void onResolvedViaFallback(String keyHex, int probed) {}

            @Override
            public void onUnresolvedAfterFallback(ResolveMiss report) {
                miss.set(report);
            }
        };
        var node = dhtNode(new NodeId("local"), memoryStorageEngine(), ring, CONFIG);
        var client = distributedDHTClient(node, network, CONFIG).withResolveFallbackObserver(observer);

        var read = client.get("k1".getBytes(StandardCharsets.UTF_8));
        var initial = sent.stream().filter(s -> s.message() instanceof DHTMessage.GetRequest).toList();
        assertThat(initial).hasSize(3);

        // one replica answers empty, one leaves the ring without answering, one answers empty
        answer(client, initial.get(0));
        ring.removeNode(initial.get(1).target());
        answerAllPending(client, sent, new HashSet<>(List.of(initial.get(1).target())));

        read.await(timeSpan(5).seconds());

        assertThat(miss.get()).isNotNull();
        assertThat(miss.get().departed()).isEqualTo(1);
        assertThat(miss.get().verdict()).isEqualTo("unreachable");
    }

    private static void answerAllPending(DistributedDHTClient client, List<Sent> sent, HashSet<NodeId> silent) {
        var answered = new HashSet<String>();
        var progressed = true;

        while (progressed) {
            progressed = false;
            for (var request : List.copyOf(sent)) {
                if (request.message() instanceof DHTMessage.GetRequest req
                    && !silent.contains(request.target())
                    && answered.add(req.requestId())) {
                    answer(client, request);
                    progressed = true;
                }
            }
        }
    }

    private static void answer(DistributedDHTClient client, Sent request) {
        var req = (DHTMessage.GetRequest) request.message();

        client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), request.target(), Option.none()));
    }
}
