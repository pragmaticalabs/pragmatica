package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class GovernorCleanupTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-worker-1").unwrap();
    private static final NodeId ALIVE_NODE = NodeId.nodeId("alive-worker-1").unwrap();

    private CapturingMutationForwarder forwarder;
    private GovernorCleanup cleanup;

    @BeforeEach
    void setUp() {
        forwarder = new CapturingMutationForwarder();
        cleanup = GovernorCleanup.governorCleanup(forwarder);
    }

    @Nested
    class CleanupDeadNode {
        @Test
        void cleanupDeadNode_removesTrackedNodeArtifacts() {
            var key = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                       org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void cleanupDeadNode_removesTrackedNodeRoutes() {
            var key = NodeRoutesKey.nodeRoutesKey(DEAD_NODE,
                                                   org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeRoutes(DEAD_NODE, key);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void cleanupDeadNode_noEntries_isNoOp() {
            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).isEmpty();
        }

        @Test
        void cleanupDeadNode_doesNotAffectOtherNodes() {
            var deadKey = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                          org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            var aliveKey = NodeArtifactKey.nodeArtifactKey(ALIVE_NODE,
                                                           org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:2.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, deadKey);
            cleanup.trackNodeArtifact(ALIVE_NODE, aliveKey);

            cleanup.cleanupDeadNode(DEAD_NODE).await();

            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void cleanupDeadNode_removesBothTypes() {
            var naKey = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                        org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            var nrKey = NodeRoutesKey.nodeRoutesKey(DEAD_NODE,
                                                    org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, naKey);
            cleanup.trackNodeRoutes(DEAD_NODE, nrKey);

            var result = cleanup.cleanupDeadNode(DEAD_NODE).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).hasSize(2);
        }
    }

    @Nested
    class CleanupDeadNodes {
        @Test
        void cleanupDeadNodes_removesOnlyDeadNodeEntries() {
            var deadKey = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                          org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            var aliveKey = NodeArtifactKey.nodeArtifactKey(ALIVE_NODE,
                                                           org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:2.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, deadKey);
            cleanup.trackNodeArtifact(ALIVE_NODE, aliveKey);

            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void cleanupDeadNodes_noDeadNodes_isNoOp() {
            var key = NodeArtifactKey.nodeArtifactKey(ALIVE_NODE,
                                                      org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeArtifact(ALIVE_NODE, key);

            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).isEmpty();
        }

        @Test
        void cleanupDeadNodes_emptyIndex_isNoOp() {
            var result = cleanup.cleanupDeadNodes(Set.of(ALIVE_NODE)).await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(forwarder.forwarded).isEmpty();
        }
    }

    @Nested
    class TrackingAndUntracking {
        @Test
        void untrackNodeArtifact_removesFromIndex_beforeCleanup() {
            var key = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                      org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, key);
            cleanup.untrackNodeArtifact(DEAD_NODE, key);

            cleanup.cleanupDeadNode(DEAD_NODE).await();

            assertThat(forwarder.forwarded).isEmpty();
        }

        @Test
        void untrackNodeRoutes_removesFromIndex_beforeCleanup() {
            var key = NodeRoutesKey.nodeRoutesKey(DEAD_NODE,
                                                   org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeRoutes(DEAD_NODE, key);
            cleanup.untrackNodeRoutes(DEAD_NODE, key);

            cleanup.cleanupDeadNode(DEAD_NODE).await();

            assertThat(forwarder.forwarded).isEmpty();
        }
    }

    @Nested
    class RebuildIndex {
        private DHTNode dhtNode;
        private MemoryStorageEngine storage;

        @BeforeEach
        void setUpDht() {
            storage = MemoryStorageEngine.memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(ALIVE_NODE);
            dhtNode = DHTNode.dhtNode(ALIVE_NODE, storage, ring, DHTConfig.DEFAULT);
        }

        @Test
        void rebuildIndex_populatesNodeArtifactIndex() {
            putStorageEntry("node-artifact/" + DEAD_NODE.id() + "/com.example:svc:1.0.0",
                            "ACTIVE");

            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            cleanup.cleanupDeadNode(DEAD_NODE).await();
            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void rebuildIndex_populatesNodeRoutesIndex() {
            putStorageEntry("node-routes/" + DEAD_NODE.id() + "/com.example:svc:1.0.0",
                            "routes-data");

            var result = cleanup.rebuildIndex(dhtNode).await();

            result.onFailure(_ -> fail("Expected success"));
            cleanup.cleanupDeadNode(DEAD_NODE).await();
            assertThat(forwarder.forwarded).hasSize(1);
        }

        @Test
        void rebuildIndex_clearsExistingIndex() {
            var key = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                      org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
            cleanup.trackNodeArtifact(DEAD_NODE, key);

            cleanup.rebuildIndex(dhtNode).await();

            cleanup.cleanupDeadNode(DEAD_NODE).await();
            assertThat(forwarder.forwarded).isEmpty();
        }

        private void putStorageEntry(String key, String value) {
            storage.put(key.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8))
                   .await();
        }
    }

    @SuppressWarnings("JBCT-STY-05")
    static class CapturingMutationForwarder implements MutationForwarder {
        final List<WorkerMutation> forwarded = new ArrayList<>();

        @Override
        public void forward(WorkerMutation mutation) { forwarded.add(mutation); }

        @Override
        public void onMutationFromFollower(WorkerMutation mutation) {}

        @Override
        public void updateGovernor(Option<NodeId> governor) {}
    }
}
