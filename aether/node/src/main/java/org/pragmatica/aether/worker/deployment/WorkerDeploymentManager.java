package org.pragmatica.aether.worker.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages slice deployment lifecycle on worker nodes.
/// Watches WorkerSliceDirective entries and self-assigns instances via consistent hashing.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "JBCT-STY-05"})
public interface WorkerDeploymentManager {
    Logger log = LoggerFactory.getLogger(WorkerDeploymentManager.class);

    /// Called when a WorkerSliceDirective is put to KVStore.
    void onDirectivePut(WorkerSliceDirectiveValue directive);

    /// Called when a WorkerSliceDirective is removed from KVStore.
    void onDirectiveRemove(Artifact artifact);

    /// Called when SWIM membership changes -- recompute instance assignments.
    void onMembershipChange(List<NodeId> aliveMembers);

    /// Deployment state for a single artifact on this worker.
    enum DeploymentState {
        IDLE,
        LOADING,
        LOADED,
        ACTIVATING,
        ACTIVE,
        FAILED
    }

    /// Tracks a single artifact deployment on this worker.
    record WorkerSliceDeployment(Artifact artifact,
                                 DeploymentState state,
                                 int assignedInstances) {
        WorkerSliceDeployment withState(DeploymentState newState) {
            return new WorkerSliceDeployment(artifact, newState, assignedInstances);
        }

        WorkerSliceDeployment withInstances(int count) {
            return new WorkerSliceDeployment(artifact, state, count);
        }
    }

    /// Create an active WorkerDeploymentManager with community identity supplier.
    static WorkerDeploymentManager workerDeploymentManager(NodeId self,
                                                           SliceStore sliceStore,
                                                           MutationForwarder mutationForwarder,
                                                           List<NodeId> initialMembers,
                                                           Supplier<String> communityIdSupplier) {
        @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "JBCT-STY-05", "JBCT-SEQ-01", "JBCT-LAM-01"}) record workerDeploymentManager( NodeId self,
                                                                                                                                      SliceStore sliceStore,
                                                                                                                                      MutationForwarder mutationForwarder,
                                                                                                                                      ConcurrentHashMap<Artifact, WorkerSliceDeployment> deployments,
                                                                                                                                      ConcurrentHashMap<Artifact, WorkerSliceDirectiveValue> directives,
                                                                                                                                      AtomicReference<List<NodeId>> aliveMembers,
                                                                                                                                      AtomicLong correlationCounter,
                                                                                                                                      Supplier<String> communityIdSupplier) implements WorkerDeploymentManager {
            @Override public void onDirectivePut(WorkerSliceDirectiveValue directive) {
                if ( isDirectiveForDifferentCommunity(directive)) {
                return;}
                var artifact = directive.artifact();
                directives.put(artifact, directive);
                log.info("Received worker directive for {} with {} target instances",
                         artifact,
                         directive.targetInstances());
                computeAndApplyAssignment(artifact, directive);
            }

            private boolean isDirectiveForDifferentCommunity(WorkerSliceDirectiveValue directive) {
                var myCommunity = communityIdSupplier.get();
                var isDifferent = directive.targetCommunity().map(target -> !target.equals(myCommunity))
                                                           .or(false);
                if ( isDifferent) {
                log.debug("Skipping directive for {} — targets community '{}', this worker is in '{}'",
                          directive.artifact(),
                          directive.targetCommunity().or(""),
                          myCommunity);}
                return isDifferent;
            }

            @Override public void onDirectiveRemove(Artifact artifact) {
                directives.remove(artifact);
                log.info("Worker directive removed for {}", artifact);
                Option.option(deployments.remove(artifact)).filter(d -> d.state() != DeploymentState.IDLE)
                             .onPresent(_ -> teardownSlice(artifact));
            }

            @Override public void onMembershipChange(List<NodeId> newMembers) {
                aliveMembers.set(List.copyOf(newMembers));
                log.debug("Membership changed to {} members, recomputing assignments", newMembers.size());
                directives.forEach(this::computeAndApplyAssignment);
            }

            private void computeAndApplyAssignment(Artifact artifact, WorkerSliceDirectiveValue directive) {
                var assigned = WorkerInstanceAssignment.assignedInstances(artifact,
                                                                          directive.targetInstances(),
                                                                          aliveMembers.get(),
                                                                          self);
                var current = Option.option(deployments.get(artifact));
                if ( assigned > 0 && needsDeploy(current)) {
                    deployments.put(artifact, new WorkerSliceDeployment(artifact, DeploymentState.LOADING, assigned));
                    loadAndActivateSlice(artifact);
                } else



























                if ( assigned == 0 && needsUndeploy(current)) {
                    deployments.remove(artifact);
                    teardownSlice(artifact);
                } else {
                current.onPresent(c -> deployments.put(artifact, c.withInstances(assigned)));}
            }

            private static boolean needsDeploy(Option<WorkerSliceDeployment> current) {
                return current.map(c -> c.state() == DeploymentState.IDLE).or(true);
            }

            private static boolean needsUndeploy(Option<WorkerSliceDeployment> current) {
                return current.map(c -> c.state() == DeploymentState.LOADED || c.state() == DeploymentState.ACTIVE)
                .or(false);
            }

            private void loadAndActivateSlice(Artifact artifact) {
                var sliceKey = new SliceNodeKey(artifact, self);
                forwardSliceStateUpdate(sliceKey, SliceState.LOADING);
                sliceStore.loadSlice(artifact).flatMap(_ -> transitionToLoaded(artifact, sliceKey))
                                    .flatMap(_ -> sliceStore.activateSlice(artifact))
                                    .flatMap(_ -> transitionToActive(artifact, sliceKey))
                                    .flatMap(_ -> publishEndpoints(artifact))
                                    .onSuccess(_ -> log.info("Slice {} fully deployed and active on worker {}",
                                                             artifact,
                                                             self.id()))
                                    .onFailure(cause -> handleDeploymentFailure(artifact, sliceKey, cause));
            }

            private Promise<Unit> transitionToLoaded(Artifact artifact, SliceNodeKey sliceKey) {
                updateDeploymentState(artifact, DeploymentState.LOADED);
                forwardSliceStateUpdate(sliceKey, SliceState.LOADED);
                return transitionToActivating(artifact, sliceKey);
            }

            private Promise<Unit> transitionToActivating(Artifact artifact, SliceNodeKey sliceKey) {
                updateDeploymentState(artifact, DeploymentState.ACTIVATING);
                forwardSliceStateUpdate(sliceKey, SliceState.ACTIVATING);
                return Promise.unitPromise();
            }

            private Promise<Unit> transitionToActive(Artifact artifact, SliceNodeKey sliceKey) {
                updateDeploymentState(artifact, DeploymentState.ACTIVE);
                forwardSliceStateUpdate(sliceKey, SliceState.ACTIVE);
                return Promise.unitPromise();
            }

            private void handleDeploymentFailure(Artifact artifact, SliceNodeKey sliceKey, Cause cause) {
                log.error("Failed to deploy slice {} on worker {}: {}", artifact, self.id(), cause.message());
                updateDeploymentState(artifact, DeploymentState.FAILED);
                forwardSliceStateUpdate(sliceKey, SliceState.FAILED);
            }

            private void teardownSlice(Artifact artifact) {
                var sliceKey = new SliceNodeKey(artifact, self);
                log.info("Tearing down slice {} on worker {}", artifact, self.id());
                unpublishEndpoints(artifact).flatMap(_ -> sliceStore.deactivateSlice(artifact))
                                  .flatMap(_ -> sliceStore.unloadSlice(artifact))
                                  .onSuccess(_ -> forwardSliceRemoval(sliceKey))
                                  .onSuccess(_ -> log.info("Slice {} torn down on worker {}",
                                                           artifact,
                                                           self.id()))
                                  .onFailure(cause -> log.error("Failed to tear down slice {} on worker {}: {}",
                                                                artifact,
                                                                self.id(),
                                                                cause.message()));
            }

            private Promise<Unit> publishEndpoints(Artifact artifact) {
                return findLoadedSlice(artifact).map(ls -> publishEndpointsForSlice(artifact,
                                                                                    ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> publishEndpointsForSlice(Artifact artifact, Slice slice) {
                var methods = slice.methods();
                if ( methods.isEmpty()) {
                return Promise.unitPromise();}
                int instanceNumber = Math.abs(self.id().hashCode());
                var methodNames = methods.stream().map(m -> m.name().name())
                                                .toList();
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, artifact);
                var nodeArtifactValue = NodeArtifactValue.activeNodeArtifactValue(instanceNumber, methodNames);
                forwardPut(nodeArtifactKey, nodeArtifactValue, "publish-endpoints-" + artifact);
                log.debug("Published {} endpoints for slice {} on worker", methods.size(), artifact);
                return Promise.unitPromise();
            }

            private Promise<Unit> unpublishEndpoints(Artifact artifact) {
                return findLoadedSlice(artifact).map(ls -> unpublishEndpointsForSlice(artifact,
                                                                                      ls.slice()))
                                      .or(Promise.unitPromise());
            }

            private Promise<Unit> unpublishEndpointsForSlice(Artifact artifact, Slice slice) {
                var methods = slice.methods();
                if ( methods.isEmpty()) {
                return Promise.unitPromise();}
                // Write NodeArtifactKey with empty methods to clear endpoint info
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, artifact);
                var nodeArtifactValue = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
                forwardPut(nodeArtifactKey, nodeArtifactValue, "unpublish-endpoints-" + artifact);
                return Promise.unitPromise();
            }

            /// Forward NodeArtifactKey state update via MutationForwarder.
            private void forwardSliceStateUpdate(SliceNodeKey sliceKey, SliceState state) {
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, sliceKey.artifact());
                var nodeArtifactValue = NodeArtifactValue.nodeArtifactValue(state);
                var correlationId = nextCorrelationId("state-" + state.name().toLowerCase());
                forwardPut(nodeArtifactKey, nodeArtifactValue, correlationId);
            }

            /// Forward removal of NodeArtifactKey via MutationForwarder.
            private void forwardSliceRemoval(SliceNodeKey sliceKey) {
                var nodeArtifactKey = NodeArtifactKey.nodeArtifactKey(self, sliceKey.artifact());
                var correlationId = nextCorrelationId("remove");
                forwardRemove(nodeArtifactKey, correlationId);
            }

            @SuppressWarnings("unchecked")
            private<K extends AetherKey> void forwardPut(K key, Object value, String correlationId) {
                KVCommand<AetherKey> command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                mutationForwarder.forward(WorkerMutation.workerMutation(self, correlationId, command));
            }

            @SuppressWarnings("unchecked")
            private<K extends AetherKey> void forwardRemove(K key, String correlationId) {
                KVCommand<AetherKey> command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);
                mutationForwarder.forward(WorkerMutation.workerMutation(self, correlationId, command));
            }

            private String nextCorrelationId(String context) {
                return "wdm-" + self.id() + "-" + correlationCounter.incrementAndGet() + "-" + context;
            }

            private void updateDeploymentState(Artifact artifact, DeploymentState state) {
                deployments.computeIfPresent(artifact, (_, current) -> current.withState(state));
            }

            private Option<SliceStore.LoadedSlice> findLoadedSlice(Artifact artifact) {
                return Option.from(sliceStore.loaded().stream()
                                                    .filter(ls -> ls.artifact().equals(artifact))
                                                    .findFirst());
            }
        }
        return new workerDeploymentManager(self,
                                           sliceStore,
                                           mutationForwarder,
                                           new ConcurrentHashMap<>(),
                                           new ConcurrentHashMap<>(),
                                           new AtomicReference<>(List.copyOf(initialMembers)),
                                           new AtomicLong(0),
                                           communityIdSupplier);
    }

    /// Create an active WorkerDeploymentManager with default community identity.
    static WorkerDeploymentManager workerDeploymentManager(NodeId self,
                                                           SliceStore sliceStore,
                                                           MutationForwarder mutationForwarder,
                                                           List<NodeId> initialMembers) {
        return workerDeploymentManager(self, sliceStore, mutationForwarder, initialMembers, () -> "default:local");
    }
}
