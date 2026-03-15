package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.consensus.NodeId;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GovernorReconciliationTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-worker-1").unwrap();
    private static final NodeId ALIVE_NODE = NodeId.nodeId("alive-worker-1").unwrap();

    private MutationForwarder forwarder;
    private GovernorCleanup cleanup;

    @BeforeEach
    void setUp() {
        forwarder = mock(MutationForwarder.class);
        cleanup = GovernorCleanup.governorCleanup(forwarder);
    }

    @Test
    void reconcile_removesDeadNodeEntries() {
        var deadKey = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                      org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
        cleanup.trackNodeArtifact(DEAD_NODE, deadKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        verify(forwarder, times(1)).forward(any(WorkerMutation.class));
    }

    @Test
    void reconcile_emptyIndex_succeeds() {
        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        verify(forwarder, never()).forward(any());
    }

    @Test
    void reconcile_aliveNodesNotRemoved() {
        var aliveKey = NodeArtifactKey.nodeArtifactKey(ALIVE_NODE,
                                                       org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
        cleanup.trackNodeArtifact(ALIVE_NODE, aliveKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        verify(forwarder, never()).forward(any());
    }
}
