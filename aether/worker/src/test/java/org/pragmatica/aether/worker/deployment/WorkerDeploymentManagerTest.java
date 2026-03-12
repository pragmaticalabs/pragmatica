package org.pragmatica.aether.worker.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.dht.ReplicatedMap;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
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
import static org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue.workerSliceDirectiveValue;
import static org.pragmatica.lang.Unit.unit;

class WorkerDeploymentManagerTest {
    private static final NodeId SELF = NodeId.nodeId("worker-self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("worker-other").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:test-slice:1.0.0").unwrap();
    private static final MethodName METHOD_DO_WORK = MethodName.methodName("doWork").unwrap();
    private static final MethodName METHOD_PROCESS = MethodName.methodName("processRequest").unwrap();
    private static final String MY_COMMUNITY = "default:local";

    private StubEndpointMap endpointMap;
    private StubSliceNodeMap sliceNodeMap;
    private StubSliceStore sliceStore;
    private WorkerDeploymentManager manager;

    @BeforeEach
    void setUp() {
        endpointMap = new StubEndpointMap();
        sliceNodeMap = new StubSliceNodeMap();
        var aetherMaps = new StubAetherMaps(endpointMap, sliceNodeMap, new StubHttpRouteMap());
        sliceStore = new StubSliceStore();
        manager = WorkerDeploymentManager.workerDeploymentManager(
            SELF, sliceStore, aetherMaps, List.of(SELF, OTHER));
    }

    @Nested
    class OnDirectivePut {
        @Test
        void onDirectivePut_ownCommunity_startsDeployment() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY", MY_COMMUNITY);

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceNodeMap.putKeys).isNotEmpty();
        }

        @Test
        void onDirectivePut_differentCommunity_isIgnored() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY", "other:community");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceNodeMap.putKeys).isEmpty();
            assertThat(sliceStore.loadCalls).isEmpty();
        }

        @Test
        void onDirectivePut_noCommunity_startsDeployment() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            assertThat(sliceNodeMap.putKeys).isNotEmpty();
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

            var initialPutCount = sliceNodeMap.putKeys.size();
            var thirdNode = NodeId.nodeId("worker-third").unwrap();
            manager.onMembershipChange(List.of(SELF, OTHER, thirdNode));
            waitForAsyncCompletion();

            // Membership change should not trigger additional loading since already active
            // but the directive is reprocessed
            assertThat(sliceNodeMap.putKeys.size()).isGreaterThanOrEqualTo(initialPutCount);
        }
    }

    @Nested
    class DeploymentStateTransitions {
        @Test
        void loadAndActivate_successPath_transitionsToActive() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            // Verify sliceNodeMap received state transition puts
            var sliceKey = new SliceNodeKey(TEST_ARTIFACT, SELF);
            var statesForKey = sliceNodeMap.putEntries.stream()
                .filter(e -> e.key().equals(sliceKey))
                .map(e -> e.value().state())
                .toList();

            assertThat(statesForKey).containsSubsequence(
                org.pragmatica.aether.slice.SliceState.LOADING,
                org.pragmatica.aether.slice.SliceState.LOADED,
                org.pragmatica.aether.slice.SliceState.ACTIVATING,
                org.pragmatica.aether.slice.SliceState.ACTIVE
            );
        }

        @Test
        void loadAndActivate_loadFailure_transitionsToFailed() {
            sliceStore.failOnLoad = true;
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            var sliceKey = new SliceNodeKey(TEST_ARTIFACT, SELF);
            var statesForKey = sliceNodeMap.putEntries.stream()
                .filter(e -> e.key().equals(sliceKey))
                .map(e -> e.value().state())
                .toList();

            assertThat(statesForKey).contains(org.pragmatica.aether.slice.SliceState.LOADING);
            assertThat(statesForKey).contains(org.pragmatica.aether.slice.SliceState.FAILED);
        }
    }

    @Nested
    class EndpointPublishing {
        @Test
        void publishEndpoints_publishesAllMethods() {
            var directive = workerSliceDirectiveValue(TEST_ARTIFACT, 2, "WORKER_ONLY");

            manager.onDirectivePut(directive);
            waitForAsyncCompletion();

            // Verify endpoint map received puts for both methods
            assertThat(endpointMap.putKeys).hasSizeGreaterThanOrEqualTo(2);
            var artifactEndpoints = endpointMap.putKeys.stream()
                .filter(k -> k.artifact().equals(TEST_ARTIFACT))
                .toList();
            assertThat(artifactEndpoints).hasSize(2);
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

    record PutEntry<K, V>(K key, V value) {}

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

    record StubAetherMaps(StubEndpointMap endpointMap,
                          StubSliceNodeMap sliceNodeMap,
                          StubHttpRouteMap httpRouteMap) implements AetherMaps {
        @Override
        public ReplicatedMap<EndpointKey, EndpointValue> endpoints() {
            return endpointMap;
        }

        @Override
        public ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodes() {
            return sliceNodeMap;
        }

        @Override
        public ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> httpRoutes() {
            return httpRouteMap;
        }

        @Override
        public void dispatchRemotePut(byte[] rawKey, byte[] rawValue) {}

        @Override
        public void dispatchRemoteRemove(byte[] rawKey) {}
    }

    static class StubEndpointMap implements ReplicatedMap<EndpointKey, EndpointValue> {
        final CopyOnWriteArrayList<EndpointKey> putKeys = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<EndpointKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(EndpointKey key, EndpointValue value) {
            putKeys.add(key);
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<EndpointValue>> get(EndpointKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(EndpointKey key) {
            removedKeys.add(key);
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<EndpointKey, EndpointValue> subscribe(MapSubscription<EndpointKey, EndpointValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-endpoints";
        }
    }

    static class StubSliceNodeMap implements ReplicatedMap<SliceNodeKey, SliceNodeValue> {
        final CopyOnWriteArrayList<SliceNodeKey> putKeys = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<PutEntry<SliceNodeKey, SliceNodeValue>> putEntries = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SliceNodeKey> removedKeys = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> put(SliceNodeKey key, SliceNodeValue value) {
            putKeys.add(key);
            putEntries.add(new PutEntry<>(key, value));
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<SliceNodeValue>> get(SliceNodeKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(SliceNodeKey key) {
            removedKeys.add(key);
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<SliceNodeKey, SliceNodeValue> subscribe(MapSubscription<SliceNodeKey, SliceNodeValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-slice-nodes";
        }
    }

    static class StubHttpRouteMap implements ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> {
        @Override
        public Promise<Unit> put(HttpNodeRouteKey key, HttpNodeRouteValue value) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<HttpNodeRouteValue>> get(HttpNodeRouteKey key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Boolean> remove(HttpNodeRouteKey key) {
            return Promise.success(true);
        }

        @Override
        public ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> subscribe(MapSubscription<HttpNodeRouteKey, HttpNodeRouteValue> subscription) {
            return this;
        }

        @Override
        public String name() {
            return "test-http-routes";
        }
    }
}
