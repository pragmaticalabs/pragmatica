package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class GovernorReconciliationTest {
    private static final NodeId DEAD_NODE = NodeId.nodeId("dead-worker-1").unwrap();
    private static final NodeId ALIVE_NODE = NodeId.nodeId("alive-worker-1").unwrap();

    private CapturingMutationForwarder forwarder;
    private GovernorCleanup cleanup;

    @BeforeEach
    void setUp() {
        forwarder = new CapturingMutationForwarder();
        cleanup = GovernorCleanup.governorCleanup(forwarder);
    }

    @Test
    void reconcile_removesDeadNodeEntries() {
        var deadKey = NodeArtifactKey.nodeArtifactKey(DEAD_NODE,
                                                      org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
        cleanup.trackNodeArtifact(DEAD_NODE, deadKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(forwarder.forwarded).hasSize(1);
    }

    @Test
    void reconcile_emptyIndex_succeeds() {
        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(forwarder.forwarded).isEmpty();
    }

    @Test
    void reconcile_aliveNodesNotRemoved() {
        var aliveKey = NodeArtifactKey.nodeArtifactKey(ALIVE_NODE,
                                                       org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap());
        cleanup.trackNodeArtifact(ALIVE_NODE, aliveKey);

        var result = GovernorReconciliation.reconcile(Set.of(ALIVE_NODE), cleanup).await();

        result.onFailure(_ -> fail("Expected success"));
        assertThat(forwarder.forwarded).isEmpty();
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
