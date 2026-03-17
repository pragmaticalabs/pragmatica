package org.pragmatica.aether.metrics.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactDeploymentTrackerTest {
    private ArtifactDeploymentTracker tracker;
    private Artifact artifact1;
    private Artifact artifact2;
    private NodeId node1;
    private NodeId node2;

    @BeforeEach
    void setup() {
        tracker = ArtifactDeploymentTracker.artifactDeploymentTracker();

        artifact1 = Artifact.artifact("org.example:slice1:1.0.0").unwrap();
        artifact2 = Artifact.artifact("org.example:slice2:2.0.0").unwrap();

        node1 = NodeId.nodeId("node-1").unwrap();
        node2 = NodeId.nodeId("node-2").unwrap();
    }

    @Test
    void deployedArtifacts_isEmpty_initially() {
        assertThat(tracker.deployedArtifacts()).isEmpty();
        assertThat(tracker.deployedCount()).isZero();
    }

    @Test
    void isDeployed_returnsFalse_forUnknownArtifact() {
        assertThat(tracker.isDeployed(artifact1)).isFalse();
    }

    @Test
    void onNodeArtifactPut_tracksDeployment_forSingleNode() {
        deployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
        assertThat(tracker.deployedArtifacts()).containsExactly(artifact1);
    }

    @Test
    void onNodeArtifactPut_tracksMultipleDeployments_forSameArtifact() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact1, node2);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
        // One unique artifact
    }

    @Test
    void onNodeArtifactPut_tracksMultipleArtifacts() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact2, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.isDeployed(artifact2)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(2);
    }

    @Test
    void onNodeArtifactRemove_removesDeployment_whenLastInstanceRemoved() {
        deployArtifact(artifact1, node1);
        undeployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isFalse();
        assertThat(tracker.deployedCount()).isZero();
    }

    @Test
    void onNodeArtifactRemove_keepsDeployment_whenOtherInstancesExist() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact1, node2);
        undeployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
    }

    @Test
    void onNodeArtifactRemove_handlesUnknownArtifact_gracefully() {
        undeployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isFalse();
        assertThat(tracker.deployedCount()).isZero();
    }

    @Test
    void deployedArtifacts_returnsImmutableCopy() {
        deployArtifact(artifact1, node1);

        var artifacts = tracker.deployedArtifacts();
        assertThatThrownBy(() -> artifacts.add(artifact2))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private void deployArtifact(Artifact artifact, NodeId nodeId) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
        var put = new KVCommand.Put<>(key, value);
        var valuePut = new ValuePut<>(put, Option.none());
        tracker.onNodeArtifactPut(valuePut);
    }

    private void undeployArtifact(Artifact artifact, NodeId nodeId) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var remove = new KVCommand.Remove<NodeArtifactKey>(key);
        var valueRemove = new ValueRemove<NodeArtifactKey, NodeArtifactValue>(remove, Option.none());
        tracker.onNodeArtifactRemove(valueRemove);
    }
}
