package org.pragmatica.aether.worker.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue.workerSliceDirectiveValue;
import static org.pragmatica.lang.Unit.unit;

class WorkerDeploymentManagerTest {
    private static final NodeId SELF = NodeId.nodeId("worker-self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("worker-other").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:test-slice:1.0.0").unwrap();
    private static final MethodName METHOD_DO_WORK = MethodName.methodName("doWork").unwrap();
    private static final MethodName METHOD_PROCESS = MethodName.methodName("processRequest").unwrap();
    private static final String MY_COMMUNITY = "default:local";

    private MutationForwarder mutationForwarder;
    private StubSliceStore sliceStore;
    private WorkerDeploymentManager manager;

    @BeforeEach
    void setUp() {
        mutationForwarder = mock(MutationForwarder.class);
        sliceStore = new StubSliceStore();
        manager = WorkerDeploymentManager.workerDeploymentManager(
            SELF, sliceStore, mutationForwarder, List.of(SELF, OTHER));
    }

    @Nested
    class OnDirectivePut {
        @Test
        void onDirectivePut_ownCommunity_startsDeployment() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY", MY_COMMUNITY);

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceStore.loadCalls).isNotEmpty();
            verify(mutationForwarder, atLeastOnce()).forward(any(WorkerMutation.class));
        }

        @Test
        void onDirectivePut_differentCommunity_isIgnored() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY", "other:community");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceStore.loadCalls).isEmpty();
            verify(mutationForwarder, never()).forward(any(WorkerMutation.class));
        }

        @Test
        void onDirectivePut_noCommunity_startsDeployment() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceStore.loadCalls).isNotEmpty();
            verify(mutationForwarder, atLeastOnce()).forward(any(WorkerMutation.class));
        }
    }

    @Nested
    class OnDirectiveRemove {
        @Test
        void onDirectiveRemove_activeDeployment_triggersTeardown() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");
            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            sliceStore.resetCalls();
            manager.onDirectiveRemove(TEST_ARTIFACT);
            waitForAsyncCompletion();

            assertThat(sliceStore.deactivateCalls).contains(TEST_ARTIFACT);
            assertThat(sliceStore.unloadCalls).contains(TEST_ARTIFACT);
        }

        @Test
        void onDirectiveRemove_noDeployment_isNoOp() {
            manager.onDirectiveRemove(TEST_ARTIFACT);
            waitForAsyncCompletion();

            assertThat(sliceStore.deactivateCalls).isEmpty();
            assertThat(sliceStore.unloadCalls).isEmpty();
        }
    }

    @Nested
    class OnMembershipChange {
        @Test
        void onMembershipChange_recomputesAssignments() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");
            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            var initialLoadCount = sliceStore.loadCalls.size();
            var thirdNode = NodeId.nodeId("worker-third").unwrap();
            manager.onMembershipChange(List.of(SELF, OTHER, thirdNode));
            waitForAsyncCompletion();

            // Membership change should not trigger additional loading since already active
            assertThat(sliceStore.loadCalls.size()).isEqualTo(initialLoadCount);
        }
    }

    @Nested
    class DeploymentStateTransitions {
        @Test
        void loadAndActivate_successPath_forwardsMutations() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            // Verify state transitions were forwarded via MutationForwarder
            var captor = ArgumentCaptor.forClass(WorkerMutation.class);
            verify(mutationForwarder, atLeastOnce()).forward(captor.capture());

            // Should have forwarded mutations for LOADING, LOADED, ACTIVATING, ACTIVE states
            // plus endpoint publishing (NodeArtifactKey with methods)
            var forwarded = captor.getAllValues();
            assertThat(forwarded).hasSizeGreaterThanOrEqualTo(4);

            // Verify NodeArtifactKey mutations were forwarded
            var nodeArtifactPuts = forwarded.stream()
                .filter(m -> m.command() instanceof KVCommand.Put<?, ?> put
                             && put.key() instanceof NodeArtifactKey nak
                             && nak.artifact().equals(TEST_ARTIFACT))
                .toList();
            assertThat(nodeArtifactPuts).isNotEmpty();
        }

        @Test
        void loadAndActivate_loadFailure_transitionsToFailed() {
            sliceStore.failOnLoad = true;
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            // Verify LOADING and FAILED state mutations were forwarded
            var captor = ArgumentCaptor.forClass(WorkerMutation.class);
            verify(mutationForwarder, atLeastOnce()).forward(captor.capture());

            var nodeArtifactStates = captor.getAllValues().stream()
                .filter(m -> m.command() instanceof KVCommand.Put<?, ?> put
                             && put.key() instanceof NodeArtifactKey)
                .map(m -> ((NodeArtifactValue) ((KVCommand.Put<?, ?>) m.command()).value()).state())
                .toList();

            assertThat(nodeArtifactStates).contains(
                org.pragmatica.aether.slice.SliceState.LOADING,
                org.pragmatica.aether.slice.SliceState.FAILED
            );
        }
    }

    @Nested
    class EndpointPublishing {
        @Test
        void publishEndpoints_forwardsNodeArtifactKey() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            // Verify NodeArtifactKey with methods was forwarded
            var captor = ArgumentCaptor.forClass(WorkerMutation.class);
            verify(mutationForwarder, atLeastOnce()).forward(captor.capture());

            var nodeArtifactPuts = captor.getAllValues().stream()
                .filter(m -> m.command() instanceof KVCommand.Put<?, ?> put
                             && put.key() instanceof NodeArtifactKey nak
                             && nak.artifact().equals(TEST_ARTIFACT)
                             && put.value() instanceof NodeArtifactValue nav
                             && !nav.methods().isEmpty())
                .toList();
            assertThat(nodeArtifactPuts).hasSize(1);

            // Verify it contains both method names
            var nav = (NodeArtifactValue) ((KVCommand.Put<?, ?>) nodeArtifactPuts.getFirst().command()).value();
            assertThat(nav.methods()).containsExactlyInAnyOrder("doWork", "processRequest");
        }
    }

    @Nested
    class AssignmentComputation {
        @Test
        void computeAssignment_zeroInstances_triggersUndeploy() {
            // First deploy
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");
            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            sliceStore.resetCalls();

            // Now set zero instances
            var zeroDirective = workerSliceDirectiveValue(TEST_ARTIFACT, 0, "WORKER_ONLY");
            manager.onDirectivePut(zeroDirective);
            waitForAsyncCompletion();

            assertThat(sliceStore.deactivateCalls).contains(TEST_ARTIFACT);
        }

        @Test
        void computeAssignment_positiveInstances_triggersDeploy() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceStore.loadCalls).contains(TEST_ARTIFACT);
            assertThat(sliceStore.activateCalls).contains(TEST_ARTIFACT);
        }

        @Test
        void computeAssignment_alreadyActive_updatesCount() {
            // Deploy with 2 instances
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");
            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            var loadCountAfterFirst = sliceStore.loadCalls.size();

            // Update to 4 instances -- should NOT reload since already active
            var updatedDirective = workerSliceDirectiveValue(TEST_ARTIFACT, 4, "WORKER_ONLY");
            manager.onDirectivePut(updatedDirective);
            waitForAsyncCompletion();

            // No additional load calls -- just instance count update
            assertThat(sliceStore.loadCalls.size()).isEqualTo(loadCountAfterFirst);
        }
    }

    // -- Helpers --

    private static void waitForAsyncCompletion() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -- Stubs --

    static class StubSliceStore implements SliceStore {
        final CopyOnWriteArrayList<Artifact> loadCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Artifact> activateCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Artifact> deactivateCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Artifact> unloadCalls = new CopyOnWriteArrayList<>();
        volatile boolean failOnLoad = false;
        private final Slice stubSlice = new StubSlice();
        private final CopyOnWriteArrayList<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        void resetCalls() {
            loadCalls.clear();
            activateCalls.clear();
            deactivateCalls.clear();
            unloadCalls.clear();
        }

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            loadCalls.add(artifact);
            if (failOnLoad) {
                return Causes.cause("Simulated load failure").promise();
            }
            var loaded = new StubLoadedSlice(artifact, stubSlice);
            loadedSlices.add(loaded);
            return Promise.success(loaded);
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            activateCalls.add(artifact);
            return Promise.success(new StubLoadedSlice(artifact, stubSlice));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            deactivateCalls.add(artifact);
            return Promise.success(new StubLoadedSlice(artifact, stubSlice));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            unloadCalls.add(artifact);
            loadedSlices.removeIf(ls -> ls.artifact().equals(artifact));
            return Promise.success(unit());
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }
    }

    record StubLoadedSlice(Artifact artifact, Slice slice) implements LoadedSlice {}

    static class StubSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(
                new SliceMethod<>(METHOD_DO_WORK, _ -> Promise.success("ok"),
                                  new TypeToken<>() {}, new TypeToken<>() {}),
                new SliceMethod<>(METHOD_PROCESS, _ -> Promise.success("ok"),
                                  new TypeToken<>() {}, new TypeToken<>() {})
            );
        }
    }
}
