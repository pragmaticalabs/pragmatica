package org.pragmatica.aether.deployment.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.NodeDeploymentState.DormantNodeDeploymentState;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SliceDeployment;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SuspendedSlice;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.consensus.NodeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeDeploymentManagerTest {

    private NodeId selfNode;
    private NodeId otherNode;
    private Artifact artifact1;
    private Artifact artifact2;

    @BeforeEach
    void setUp() {
        selfNode = NodeId.randomNodeId();
        otherNode = NodeId.randomNodeId();
        artifact1 = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        artifact2 = Artifact.artifact("org.example:slice-b:2.0.0").unwrap();
    }

    @Nested
    class SliceDeploymentRecord {
        @Test
        void sliceDeployment_creation_capturesKeyStateAndTimestamp() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var timestamp = System.currentTimeMillis();
            var deployment = new SliceDeployment(key, SliceState.ACTIVE, timestamp);

            assertThat(deployment.key()).isEqualTo(key);
            assertThat(deployment.state()).isEqualTo(SliceState.ACTIVE);
            assertThat(deployment.timestamp()).isEqualTo(timestamp);
        }

        @Test
        void sliceDeployment_equality_basedOnAllFields() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var timestamp = 1234567890L;
            var d1 = new SliceDeployment(key, SliceState.LOADED, timestamp);
            var d2 = new SliceDeployment(key, SliceState.LOADED, timestamp);

            assertThat(d1).isEqualTo(d2);
        }

        @Test
        void sliceDeployment_inequality_differentState() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var timestamp = 1234567890L;
            var d1 = new SliceDeployment(key, SliceState.LOADED, timestamp);
            var d2 = new SliceDeployment(key, SliceState.ACTIVE, timestamp);

            assertThat(d1).isNotEqualTo(d2);
        }
    }

    @Nested
    class SuspendedSliceRecord {
        @Test
        void suspendedSlice_creation_capturesKeyAndDeployment() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var deployment = new SliceDeployment(key, SliceState.ACTIVE, System.currentTimeMillis());
            var suspended = new SuspendedSlice(key, deployment);

            assertThat(suspended.key()).isEqualTo(key);
            assertThat(suspended.deployment()).isEqualTo(deployment);
            assertThat(suspended.deployment().state()).isEqualTo(SliceState.ACTIVE);
        }

        @Test
        void suspendedSlice_equality_basedOnKeyAndDeployment() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var timestamp = 999L;
            var deployment = new SliceDeployment(key, SliceState.ACTIVE, timestamp);
            var s1 = new SuspendedSlice(key, deployment);
            var s2 = new SuspendedSlice(key, deployment);

            assertThat(s1).isEqualTo(s2);
        }
    }

    @Nested
    class DormantState {
        @Test
        void dormantState_defaultConstructor_hasEmptySuspendedSlices() {
            var dormant = new DormantNodeDeploymentState();

            assertThat(dormant.suspendedSlices()).isEmpty();
        }

        @Test
        void dormantState_withSuspendedSlices_retainsList() {
            var key = new SliceNodeKey(artifact1, selfNode);
            var deployment = new SliceDeployment(key, SliceState.ACTIVE, 1000L);
            var suspended = new SuspendedSlice(key, deployment);
            var dormant = new DormantNodeDeploymentState(List.of(suspended));

            assertThat(dormant.suspendedSlices()).hasSize(1);
            assertThat(dormant.suspendedSlices().getFirst().key()).isEqualTo(key);
        }

        @Test
        void dormantState_withMultipleSuspendedSlices_retainsAll() {
            var key1 = new SliceNodeKey(artifact1, selfNode);
            var key2 = new SliceNodeKey(artifact2, selfNode);
            var d1 = new SliceDeployment(key1, SliceState.ACTIVE, 1000L);
            var d2 = new SliceDeployment(key2, SliceState.ACTIVE, 2000L);
            var suspended = List.of(new SuspendedSlice(key1, d1), new SuspendedSlice(key2, d2));
            var dormant = new DormantNodeDeploymentState(suspended);

            assertThat(dormant.suspendedSlices()).hasSize(2);
        }
    }

    @Nested
    class SliceNodeKeyFiltering {
        @Test
        void sliceNodeKey_isForNode_matchesSameNode() {
            var key = new SliceNodeKey(artifact1, selfNode);

            assertThat(key.isForNode(selfNode)).isTrue();
        }

        @Test
        void sliceNodeKey_isForNode_rejectsDifferentNode() {
            var key = new SliceNodeKey(artifact1, selfNode);

            assertThat(key.isForNode(otherNode)).isFalse();
        }

        @Test
        void sliceNodeKey_artifact_returnsCorrectArtifact() {
            var key = new SliceNodeKey(artifact1, selfNode);

            assertThat(key.artifact()).isEqualTo(artifact1);
        }

        @Test
        void sliceNodeKey_nodeId_returnsCorrectNode() {
            var key = new SliceNodeKey(artifact1, selfNode);

            assertThat(key.nodeId()).isEqualTo(selfNode);
        }
    }

    @Nested
    class DeploymentStateLifecycle {
        @Test
        void sliceState_loadIsNotTransitional() {
            assertThat(SliceState.LOAD.isTransitional()).isFalse();
        }

        @Test
        void sliceState_loadingIsTransitional() {
            assertThat(SliceState.LOADING.isTransitional()).isTrue();
        }

        @Test
        void sliceState_activeIsNotInProgress() {
            assertThat(SliceState.ACTIVE.isInProgress()).isFalse();
        }

        @Test
        void sliceState_activatingIsInProgress() {
            assertThat(SliceState.ACTIVATING.isInProgress()).isTrue();
        }

        @Test
        void sliceState_failedIsNotInProgress() {
            assertThat(SliceState.FAILED.isInProgress()).isFalse();
        }

        @Test
        void sliceState_loadCanTransitionToLoading() {
            assertThat(SliceState.LOAD.canTransitionTo(SliceState.LOADING)).isTrue();
        }

        @Test
        void sliceState_loadCannotTransitionToActive() {
            assertThat(SliceState.LOAD.canTransitionTo(SliceState.ACTIVE)).isFalse();
        }

        @Test
        void sliceState_failedCanOnlyTransitionToUnload() {
            assertThat(SliceState.FAILED.validTransitions()).containsExactly(SliceState.UNLOAD);
        }

        @Test
        void sliceState_unloadingIsTerminal() {
            assertThat(SliceState.UNLOADING.validTransitions()).isEmpty();
        }
    }
}
