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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

class DHTAntiEntropyTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId PEER = new NodeId("peer");

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class DigestComputation {
        @Test
        void computeDigest_isDeterministic_forSameEntries() {
            var entries = List.of(
                new DHTMessage.KeyValue(key("a"), value("1")),
                new DHTMessage.KeyValue(key("b"), value("2"))
            );

            var digest1 = DHTNode.computeDigest(entries);
            var digest2 = DHTNode.computeDigest(entries);

            assertThat(digest1).isEqualTo(digest2);
        }

        @Test
        void computeDigest_isDeterministic_regardlessOfInputOrder() {
            var ordered = List.of(
                new DHTMessage.KeyValue(key("a"), value("1")),
                new DHTMessage.KeyValue(key("b"), value("2"))
            );
            var reversed = List.of(
                new DHTMessage.KeyValue(key("b"), value("2")),
                new DHTMessage.KeyValue(key("a"), value("1"))
            );

            var digest1 = DHTNode.computeDigest(ordered);
            var digest2 = DHTNode.computeDigest(reversed);

            assertThat(digest1).isEqualTo(digest2);
        }

        @Test
        void computeDigest_differs_forDifferentEntries() {
            var entries1 = List.of(new DHTMessage.KeyValue(key("a"), value("1")));
            var entries2 = List.of(new DHTMessage.KeyValue(key("a"), value("2")));

            var digest1 = DHTNode.computeDigest(entries1);
            var digest2 = DHTNode.computeDigest(entries2);

            assertThat(digest1).isNotEqualTo(digest2);
        }

        @Test
        void computeDigest_producesNonEmptyResult_forEmptyEntries() {
            var digest = DHTNode.computeDigest(List.of());

            assertThat(digest).hasSize(8);
        }
    }

    @Nested
    class DigestResponseHandling {
        private DHTNode node;
        private CapturingNetwork network;
        private DHTAntiEntropy antiEntropy;

        @BeforeEach
        void setUp() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER);
            node = dhtNode(LOCAL, storage, ring, new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT));
            network = new CapturingNetwork();
            antiEntropy = dhtAntiEntropy(node, network, new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT));
        }

        @Test
        void onDigestResponse_noAction_whenDigestsMatch() {
            // Simulate a pending digest with known local digest
            var localDigest = DHTNode.computeDigest(List.of());
            var requestId = "test-req-1";
            injectPendingDigest(requestId, PEER, 0, localDigest);

            // Respond with matching digest
            antiEntropy.onDigestResponse(new DHTMessage.DigestResponse(requestId, PEER, localDigest));

            // No migration data request should be sent
            var migrationRequests = network.captured.stream()
                                                     .filter(m -> m.message() instanceof DHTMessage.MigrationDataRequest)
                                                     .toList();
            assertThat(migrationRequests).isEmpty();
        }

        @Test
        void onDigestResponse_requestsMigration_whenDigestsDiffer() {
            var localDigest = DHTNode.computeDigest(List.of());
            var remoteDigest = DHTNode.computeDigest(List.of(new DHTMessage.KeyValue(key("x"), value("y"))));
            var requestId = "test-req-2";
            injectPendingDigest(requestId, PEER, 5, localDigest);

            // Respond with different digest
            antiEntropy.onDigestResponse(new DHTMessage.DigestResponse(requestId, PEER, remoteDigest));

            // Should send migration data request
            var migrationRequests = network.captured.stream()
                                                     .filter(m -> m.message() instanceof DHTMessage.MigrationDataRequest)
                                                     .toList();
            assertThat(migrationRequests).hasSize(1);

            var req = (DHTMessage.MigrationDataRequest) migrationRequests.getFirst().message();
            assertThat(req.partitionStart()).isEqualTo(5);
            assertThat(req.sender()).isEqualTo(LOCAL);
        }

        @Test
        void onDigestResponse_ignoresUnknownRequestId() {
            var digest = DHTNode.computeDigest(List.of());

            // Respond with unknown request ID
            antiEntropy.onDigestResponse(new DHTMessage.DigestResponse("unknown-id", PEER, digest));

            // No migration request sent
            assertThat(network.captured).isEmpty();
        }

        @Test
        void onDigestResponse_removesPendingEntry() {
            var localDigest = DHTNode.computeDigest(List.of());
            var requestId = "test-req-3";
            injectPendingDigest(requestId, PEER, 0, localDigest);

            assertThat(antiEntropy.pendingDigestCount()).isEqualTo(1);

            antiEntropy.onDigestResponse(new DHTMessage.DigestResponse(requestId, PEER, localDigest));

            assertThat(antiEntropy.pendingDigestCount()).isEqualTo(0);
        }

        private void injectPendingDigest(String requestId, NodeId peer, int partitionIndex, byte[] localDigest) {
            // Use reflection-free approach: send a digest request and intercept it to get the correlation ID
            // Simpler: directly test via the public onDigestResponse method with known pending state
            // We need access to pendingDigests — use the package-private constructor path
            // Actually, since PendingDigest and pendingDigests are package-private, we can access them from the test
            try {
                var field = DHTAntiEntropy.class.getDeclaredField("pendingDigests");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.concurrent.ConcurrentHashMap<String, DHTAntiEntropy.PendingDigest>) field.get(antiEntropy);
                map.put(requestId, new DHTAntiEntropy.PendingDigest(peer, partitionIndex, localDigest));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    class MigrationDataResponseHandling {
        @Test
        void onMigrationDataResponse_appliesEntries_toLocalStorage() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, storage, ring, DHTConfig.SINGLE_NODE);
            var network = new CapturingNetwork();
            var antiEntropy = dhtAntiEntropy(node, network, DHTConfig.SINGLE_NODE);

            var entries = List.of(
                new DHTMessage.KeyValue(key("repaired-k1"), value("repaired-v1")),
                new DHTMessage.KeyValue(key("repaired-k2"), value("repaired-v2"))
            );

            antiEntropy.onMigrationDataResponse(
                new DHTMessage.MigrationDataResponse("mig-1", PEER, entries));

            // Verify entries were applied to local storage
            node.getLocal(key("repaired-k1"))
                .await()
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
            node.getLocal(key("repaired-k2"))
                .await()
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void onMigrationDataResponse_ignoresEmptyEntries() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, storage, ring, DHTConfig.SINGLE_NODE);
            var network = new CapturingNetwork();
            var antiEntropy = dhtAntiEntropy(node, network, DHTConfig.SINGLE_NODE);

            antiEntropy.onMigrationDataResponse(
                new DHTMessage.MigrationDataResponse("mig-2", PEER, List.of()));

            // No storage changes
            assertThat(node.localSize()).isEqualTo(0);
        }
    }

    // --- Test infrastructure ---

    private record CapturedMessage(NodeId target, ProtocolMessage message) {}

    private static final class CapturingNetwork implements ClusterNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            captured.add(new CapturedMessage(nodeId, message));
            return Unit.unit();
        }

        @Override
        public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

        @Override
        public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(NetworkServiceMessage.Send send) {}

        @Override
        public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override
        public void handlePing(NetworkMessage.Ping ping) {}

        @Override
        public void handlePong(NetworkMessage.Pong pong) {}

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int connectedNodeCount() {
            return 0;
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of();
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }
    }
}
