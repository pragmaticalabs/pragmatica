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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #610: endpoints / per-node slice state / HTTP routes are DHT `ReplicatedMap`s run at
/// `DHTConfig.FULL` (W=1/R=1, eventually consistent) with anti-entropy and rebalancing disabled.
/// [DHTAntiEntropy#runAntiEntropy] bails immediately on `config.isFullReplication()`, so a node
/// that misses a write holds a stale value with nothing to reconcile it until the key is next
/// written. This is the ticket's required first step: drop ONE node's copy of a SINGLE write at
/// the transport level (no crash, no partition — a plain lost message, the ordinary case a
/// fire-and-forget replica write can suffer) and assert convergence WITHOUT any subsequent write
/// to that key, bounded by a short timeout.
///
/// Three-node in-process cluster over a shared in-memory dispatch network, mirroring the
/// `DHTChurnSurvivalTest` harness — no full aether topology needed to reach this bug.
class DHTFullReplicationConvergenceTest {
    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(30)
    void fullReplicationWrite_droppedOnOneNode_convergesWithoutSubsequentWrite() {
        var cluster = new FullReplicationCluster(3);
        var missingHolder = new NodeId("node-2");
        var k = key("endpoints/svc-a");
        var v = value("node-7");

        cluster.dropNextPutTo(missingHolder);
        cluster.member(new NodeId("node-0")).client().put(k, v).await();

        // Prerequisite: the write actually succeeded overall (quorum=1 under FULL is satisfied by
        // the other two nodes) while the targeted node genuinely never received it.
        assertThat(cluster.holds(missingHolder, k)).as("write was dropped on the target node").isFalse();

        cluster.startAntiEntropy(TimeSpan.timeSpan(50).millis());
        try {
            var converged = cluster.awaitConvergence(k, v, TimeSpan.timeSpan(3).seconds());

            assertThat(converged)
                .as("#610: a write dropped on one node under DHTConfig.FULL must self-heal via " +
                    "anti-entropy without a subsequent write to the same key — today " +
                    "DHTAntiEntropy.runAntiEntropy() returns immediately for " +
                    "config.isFullReplication(), so the dropped node never converges")
                .isTrue();
        } finally {
            cluster.stopAntiEntropy();
        }
    }

    // --- in-process multi-node harness (pattern shared with DHTChurnSurvivalTest) ---

    private record Member(NodeId id, DHTNode node, DistributedDHTClient client, DHTAntiEntropy antiEntropy) {}

    private static final class FullReplicationCluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final Set<NodeId> dropNextPutTo = ConcurrentHashMap.newKeySet();

        FullReplicationCluster(int size) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            for (int i = 0; i < size; i++) {
                ring.addNode(new NodeId("node-" + i));
            }
            for (int i = 0; i < size; i++) {
                var id = new NodeId("node-" + i);
                var storage = memoryStorageEngine();
                var node = dhtNode(id, storage, ring, DHTConfig.FULL);
                DHTNetwork network = this::deliver;
                var client = distributedDHTClient(node, network, DHTConfig.FULL);
                var antiEntropy = dhtAntiEntropy(node, network, DHTConfig.FULL, TimeSpan.timeSpan(50).millis());

                members.put(id, new Member(id, node, client, antiEntropy));
            }
        }

        Member member(NodeId id) {
            return members.get(id);
        }

        /// One-shot: the NEXT `PutRequest` delivered to `target` is silently dropped, simulating a
        /// lost replica write. Delivery to every other node, and every later write to `target`, is
        /// unaffected.
        void dropNextPutTo(NodeId target) {
            dropNextPutTo.add(target);
        }

        boolean holds(NodeId id, byte[] key) {
            return matches(members.get(id), key, null, false);
        }

        void startAntiEntropy(TimeSpan interval) {
            members.values().forEach(m -> m.antiEntropy().start());
        }

        void stopAntiEntropy() {
            members.values().forEach(m -> m.antiEntropy().stop());
        }

        boolean awaitConvergence(byte[] key, byte[] expected, TimeSpan bound) {
            var deadline = System.currentTimeMillis() + bound.millis();

            while (System.currentTimeMillis() < deadline) {
                if (members.values().stream().allMatch(m -> matches(m, key, expected, true))) {
                    return true;
                }

                sleepQuietly(20);
            }

            return members.values().stream().allMatch(m -> matches(m, key, expected, true));
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static boolean matches(Member member, byte[] key, byte[] expected, boolean compareValue) {
            var stored = member.node().getLocal(key).await().or(Option.<byte[]> none());

            if (!compareValue) {
                return stored.isPresent();
            }

            return stored.map(actual -> Arrays.equals(actual, expected)).or(false);
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            var member = members.get(target);
            if (member == null) {
                return; // target has departed — dropped, as on a real halted node
            }

            switch (message) {
                case DHTMessage.PutRequest req -> {
                    if (dropNextPutTo.remove(target)) {
                        return; // simulated lost write — no response, matching a dropped datagram
                    }
                    member.node().handlePutRequest(req, resp -> deliver(req.sender(), resp));
                }
                case DHTMessage.PutResponse resp -> member.client().onPutResponse(resp);
                case DHTMessage.DigestRequest req -> member.node().handleDigestRequest(req, resp -> deliver(req.sender(), resp));
                case DHTMessage.DigestResponse resp -> member.antiEntropy().onDigestResponse(resp);
                case DHTMessage.MigrationDataRequest req -> member.node().handleMigrationDataRequest(req, resp -> deliver(req.sender(), resp));
                case DHTMessage.MigrationDataResponse resp -> member.antiEntropy().onMigrationDataResponse(resp);
                default -> { }
            }
        }
    }
}
