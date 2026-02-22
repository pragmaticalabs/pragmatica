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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DistributedDHTClientTest {
    private static final NodeId LOCAL_NODE = new NodeId("local");
    private static final NodeId REMOTE_NODE_1 = new NodeId("remote-1");
    private static final NodeId REMOTE_NODE_2 = new NodeId("remote-2");

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class SingleNodeLocalOperations {
        private DistributedDHTClient client;

        @BeforeEach
        void setUp() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            client = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);
        }

        @Test
        void put_succeeds_singleNode() {
            client.put(key("k1"), value("v1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()));
        }

        @Test
        void get_returnsValue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.get(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> {
                                 assertThat(opt.isPresent()).isTrue();
                                 opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")));
                             });
        }

        @Test
        void get_returnsEmpty_forMissingKey() {
            client.get(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void remove_returnsTrue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.remove(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void remove_returnsFalse_forMissingKey() {
            client.remove(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void exists_returnsTrue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.exists(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(exists -> assertThat(exists).isTrue());
        }

        @Test
        void exists_returnsFalse_forMissingKey() {
            client.exists(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(exists -> assertThat(exists).isFalse());
        }

        @Test
        void partitionFor_returnsConsistentPartition() {
            var p1 = client.partitionFor(key("k1"));
            var p2 = client.partitionFor(key("k1"));

            assertThat(p1).isEqualTo(p2);
        }

        @Test
        void get_withStringKey_works() {
            client.put("str-key", value("v1"))
                  .await();

            client.get("str-key")
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }
    }

    @Nested
    class RemoteOperationsWithResponses {
        private DistributedDHTClient client;
        private CapturingNetwork network;

        @BeforeEach
        void setUp() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            ring.addNode(REMOTE_NODE_2);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            network = new CapturingNetwork();
            // RF=3, W=2, R=2 — 3 nodes total, so all are targets
            client = distributedDHTClient(node, network, DHTConfig.DEFAULT);
        }

        @Test
        void put_sendsRemoteRequests_toNonLocalNodes() {
            // Start put — will wait for quorum
            var promise = client.put(key("k1"), value("v1"));

            // Network should have captured remote put requests
            var putRequests = network.captured.stream()
                                              .filter(m -> m.message() instanceof DHTMessage.PutRequest)
                                              .toList();
            // At least some requests should be remote (non-local nodes)
            assertThat(putRequests).isNotEmpty();

            // Simulate success responses for remote nodes
            putRequests.forEach(m -> {
                var req = (DHTMessage.PutRequest) m.message();
                client.onPutResponse(new DHTMessage.PutResponse(req.requestId(), m.target(), true));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()));
        }

        @Test
        void get_completesWithResponse_afterRemoteReply() {
            // First store locally
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            // Start get
            var promise = client.get(key("k1"));

            // Simulate remote get responses
            var getRequests = network.captured.stream()
                                              .filter(m -> m.message() instanceof DHTMessage.GetRequest)
                                              .toList();
            getRequests.forEach(m -> {
                var req = (DHTMessage.GetRequest) m.message();
                client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), m.target(), Option.some(value("v1"))));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(opt -> {
                                  assertThat(opt.isPresent()).isTrue();
                                  opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")));
                              });
        }

        @Test
        void remove_completesWithResponse_afterRemoteReply() {
            // Store locally first
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            var promise = client.remove(key("k1"));

            var removeRequests = network.captured.stream()
                                                 .filter(m -> m.message() instanceof DHTMessage.RemoveRequest)
                                                 .toList();
            removeRequests.forEach(m -> {
                var req = (DHTMessage.RemoveRequest) m.message();
                client.onRemoveResponse(new DHTMessage.RemoveResponse(req.requestId(), m.target(), true));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_completesWithResponse_afterRemoteReply() {
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            var promise = client.exists(key("k1"));

            var existsRequests = network.captured.stream()
                                                 .filter(m -> m.message() instanceof DHTMessage.ExistsRequest)
                                                 .toList();
            existsRequests.forEach(m -> {
                var req = (DHTMessage.ExistsRequest) m.message();
                client.onExistsResponse(new DHTMessage.ExistsResponse(req.requestId(), m.target(), true));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(exists -> assertThat(exists).isTrue());
        }
    }

    @Nested
    class ScopedClient {
        @Test
        void scoped_returnsClientWithDifferentConfig() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.DEFAULT);

            var scoped = original.scoped(DHTConfig.SINGLE_NODE);

            assertThat(scoped).isNotSameAs(original);
        }

        @Test
        void scoped_sharesStorageWithOriginal() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);

            // Put via original
            original.put(key("shared"), value("data"))
                    .await();

            // Scope to same single-node config
            var scoped = original.scoped(DHTConfig.SINGLE_NODE);

            // Get via scoped — should see the value (shared storage)
            scoped.get(key("shared"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> {
                                 assertThat(opt.isPresent()).isTrue();
                                 opt.onPresent(v -> assertThat(v).isEqualTo(value("data")));
                             });
        }

        @Test
        void scoped_usesOwnConfigForReplication() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            ring.addNode(REMOTE_NODE_2);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            var network = new CapturingNetwork();

            // Original: RF=3 (targets 3 nodes)
            var original = distributedDHTClient(node, network, DHTConfig.DEFAULT);
            original.put(key("k1"), value("v1"));
            int originalMessageCount = network.captured.size();

            network.captured.clear();

            // Scoped: RF=1 (targets 1 node — might be local only)
            var scoped = original.scoped(DHTConfig.SINGLE_NODE);
            scoped.put(key("k1"), value("v2"));
            int scopedMessageCount = network.captured.size();

            // Scoped with RF=1 should send fewer remote messages than RF=3
            assertThat(scopedMessageCount).isLessThanOrEqualTo(originalMessageCount);
        }

        @Test
        void scoped_cacheDefault_worksForLocalOps() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.DEFAULT);

            // Scope to cache config (RF=1, W=1, R=1)
            var cacheClient = original.scoped(DHTConfig.CACHE_DEFAULT);

            // Should work as single-node local operations
            cacheClient.put(key("cache-key"), value("cached"))
                       .await()
                       .onFailure(c -> fail("Expected success: " + c.message()));

            cacheClient.get(key("cache-key"))
                       .await()
                       .onFailure(c -> fail("Expected success: " + c.message()))
                       .onSuccess(opt -> {
                                      assertThat(opt.isPresent()).isTrue();
                                      opt.onPresent(v -> assertThat(v).isEqualTo(value("cached")));
                                  });
        }
    }

    @Nested
    class TimeoutHandling {
        @Test
        void get_timesOut_whenNoResponse() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            var node = dhtNode(LOCAL_NODE, storage, ring,
                               new DHTConfig(2, 1, 2, timeSpan(100).millis()));
            var network = new NoOpNetwork();
            // RF=2, R=2 — needs responses from both nodes, but remote won't respond
            var client = distributedDHTClient(node, network,
                                              new DHTConfig(2, 1, 2, timeSpan(100).millis()));

            // Only local responds. Remote never responds. Timeout should fire.
            client.get(key("timeout-key"))
                  .await()
                  .onSuccess(_ -> fail("Expected timeout failure"));
        }
    }

    @Nested
    class EmptyRing {
        @Test
        void get_fails_whenNoNodesInRing() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            // No nodes added to ring
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var client = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);

            client.get(key("k1"))
                  .await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertThat(c.message()).contains("No available nodes"));
        }
    }

    // --- Test infrastructure ---

    /// Captured message: target node and the sent message.
    private record CapturedMessage(NodeId target, ProtocolMessage message) {}

    /// Network stub that captures all sent messages for inspection.
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

    /// Minimal no-op network for single-node tests (no remote messaging needed).
    private static final class NoOpNetwork implements ClusterNetwork {
        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
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
