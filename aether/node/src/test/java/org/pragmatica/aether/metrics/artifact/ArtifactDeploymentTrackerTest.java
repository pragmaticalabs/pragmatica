package org.pragmatica.aether.metrics.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
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
    void onSliceNodePut_tracksDeployment_forSingleNode() {
        deployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
        assertThat(tracker.deployedArtifacts()).containsExactly(artifact1);
    }

    @Test
    void onSliceNodePut_tracksMultipleDeployments_forSameArtifact() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact1, node2);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
        // One unique artifact
    }

    @Test
    void onSliceNodePut_tracksMultipleArtifacts() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact2, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.isDeployed(artifact2)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(2);
    }

    @Test
    void onSliceNodeRemove_removesDeployment_whenLastInstanceRemoved() {
        deployArtifact(artifact1, node1);
        undeployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isFalse();
        assertThat(tracker.deployedCount()).isZero();
    }

    @Test
    void onSliceNodeRemove_keepsDeployment_whenOtherInstancesExist() {
        deployArtifact(artifact1, node1);
        deployArtifact(artifact1, node2);
        undeployArtifact(artifact1, node1);

        assertThat(tracker.isDeployed(artifact1)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);
    }

    @Test
    void onSliceNodeRemove_handlesUnknownArtifact_gracefully() {
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
        var sliceNodeKey = new SliceNodeKey(artifact, nodeId);
        var sliceNodeValue = new SliceNodeValue(SliceState.ACTIVE);
        var put = new KVCommand.Put<SliceNodeKey, SliceNodeValue>(sliceNodeKey, sliceNodeValue);
        var valuePut = new ValuePut<SliceNodeKey, SliceNodeValue>(put, Option.none());
        tracker.onSliceNodePut(valuePut);
    }

    private void undeployArtifact(Artifact artifact, NodeId nodeId) {
        var sliceNodeKey = new SliceNodeKey(artifact, nodeId);
        var remove = new KVCommand.Remove<SliceNodeKey>(sliceNodeKey);
        var valueRemove = new ValueRemove<SliceNodeKey, SliceNodeValue>(remove, Option.none());
        tracker.onSliceNodeRemove(valueRemove);
    }
}
