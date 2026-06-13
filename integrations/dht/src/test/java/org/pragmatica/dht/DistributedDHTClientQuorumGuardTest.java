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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Fix-2 defense-in-depth at the client seam: when liveness filtering shrinks the replica target
/// set below the configured quorum, the op must fail fast with [`DHTError.QuorumNotReached`]
/// SYNCHRONOUSLY — not stall to the per-op timeout. This is the scale-down zombie-write guard: the
/// ring still owns the now-departed replicas, but `livePeers()` no longer lists them, so quorum is
/// arithmetically unreachable and the client returns immediately (letting ArtifactStore's
/// transient-failure retry kick in at once instead of paying a 30s hang per chunk).
class DistributedDHTClientQuorumGuardTest {
    private static final NodeId LOCAL_NODE = new NodeId("local");
    private static final NodeId REMOTE_NODE_1 = new NodeId("remote-1");
    private static final NodeId REMOTE_NODE_2 = new NodeId("remote-2");
    /// A generous op timeout: the fast-fail must resolve the promise long before this could fire,
    /// so an assertion that the promise is ALREADY resolved on return proves the guard, not luck.
    private static final TimeSpan LONG_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final DHTConfig RF3_W2_LONG = new DHTConfig(3, 2, 2, LONG_TIMEOUT);

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private DistributedDHTClient client;
    private SilentNetwork network;

    @BeforeEach
    void setUp() {
        var storage = memoryStorageEngine();
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(LOCAL_NODE);
        ring.addNode(REMOTE_NODE_1);
        ring.addNode(REMOTE_NODE_2);
        var node = dhtNode(LOCAL_NODE, storage, ring, RF3_W2_LONG);
        // livePeers() reports ONLY the local node — the two remotes have drained out of the routing
        // view (Fix 1). The ring still owns all three, so after liveness filtering targets=1 < W=2.
        network = new SilentNetwork(Set.of(LOCAL_NODE));
        client = distributedDHTClient(node, network, RF3_W2_LONG);
    }

    @Test
    void put_liveTargetsBelowWriteQuorum_failsFastWithQuorumNotReached() {
        var promise = client.put(key("k1"), value("v1"));

        assertThat(promise.isResolved())
                .as("quorum is arithmetically unreachable (1 live target < W=2) — must fail synchronously")
                .isTrue();
        assertThat(network.sendCount)
                .as("no remote request is dispatched when quorum is unreachable up front")
                .isZero();
        promise.await()
               .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
    }

    @Test
    void get_liveTargetsBelowReadQuorum_failsFastWithQuorumNotReached() {
        var promise = client.get(key("k1"));

        assertThat(promise.isResolved())
                .as("quorum is arithmetically unreachable (1 live target < R=2) — must fail synchronously")
                .isTrue();
        promise.await()
               .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
    }

    @Test
    void put_liveTargetsMeetWriteQuorum_succeedsAtQuorum() {
        // Local + one live remote = 2 live targets, meeting W=2. The local replica succeeds and the
        // remote replies success → quorum reached, no hang.
        var storage = memoryStorageEngine();
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(LOCAL_NODE);
        ring.addNode(REMOTE_NODE_1);
        ring.addNode(REMOTE_NODE_2);
        var node = dhtNode(LOCAL_NODE, storage, ring, RF3_W2_LONG);
        var twoLive = new SilentNetwork(Set.of(LOCAL_NODE, REMOTE_NODE_1, REMOTE_NODE_2));
        var healthyClient = distributedDHTClient(node, twoLive, RF3_W2_LONG);

        var promise = healthyClient.put(key("k1"), value("v1"));

        // Reply success for every captured remote put so the remote replica counts toward quorum.
        twoLive.capturedPutRequestIds.forEach(id ->
            healthyClient.onPutResponse(new DHTMessage.PutResponse(id.requestId(), id.target(), true, false)));

        promise.await()
               .onFailure(cause -> fail("Expected success: " + cause.message()));
    }

    /// Network stub with a configurable `livePeers()` set. `sendOutcome` reports `Sent` and the
    /// request is NEVER answered — so any op that is allowed to dispatch would hang to the per-op
    /// timeout. The fast-fail guard must therefore prevent the dispatch entirely.
    private static final class SilentNetwork implements DHTNetwork {
        private final Set<NodeId> live;
        int sendCount = 0;
        final List<CapturedPut> capturedPutRequestIds = new CopyOnWriteArrayList<>();

        private SilentNetwork(Set<NodeId> live) {
            this.live = live;
        }

        @Override
        public void send(NodeId target, ProtocolMessage message) {
            sendCount++;
            recordPut(target, message);
        }

        private void recordPut(NodeId target, ProtocolMessage message) {
            if (message instanceof DHTMessage.PutRequest put) {
                capturedPutRequestIds.add(new CapturedPut(target, put.requestId()));
            }
        }

        @Override
        public Set<NodeId> livePeers() {
            return live;
        }
    }

    private record CapturedPut(NodeId target, String requestId) {}
}
