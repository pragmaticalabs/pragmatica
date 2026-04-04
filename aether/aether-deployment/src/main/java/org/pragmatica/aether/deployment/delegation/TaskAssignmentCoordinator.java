package org.pragmatica.aether.deployment.delegation;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue.AssignmentStatus;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Leader-side component that manages task group assignments.
/// Only active on the leader node. This is the ONE component that
/// still listens to `onLeaderChange` directly.
@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks --- void required by messaging framework
public sealed interface TaskAssignmentCoordinator {
    TimeSpan DEFAULT_RECONCILE_INTERVAL = timeSpan(5).seconds();

    long FAILURE_COOLDOWN_MS = 30_000L;

    @MessageReceiver void onLeaderChange(LeaderChange leaderChange);
    @MessageReceiver void onTopologyChange(TopologyChangeNotification notification);
    Map<TaskGroup, TaskAssignmentValue> assignments();
    Result<Unit> reassign(TaskGroup group, NodeId target);

    static TaskAssignmentCoordinator taskAssignmentCoordinator(NodeId self,
                                                               ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                               KVStore<AetherKey, AetherValue> kvStore,
                                                               TopologyManager topologyManager) {
        return taskAssignmentCoordinator(self, clusterNode, kvStore, topologyManager, DEFAULT_RECONCILE_INTERVAL);
    }

    static TaskAssignmentCoordinator taskAssignmentCoordinator(NodeId self,
                                                               ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                               KVStore<AetherKey, AetherValue> kvStore,
                                                               TopologyManager topologyManager,
                                                               TimeSpan reconcileInterval) {
        return new taskAssignmentCoordinator(self,
                                             clusterNode,
                                             kvStore,
                                             topologyManager,
                                             reconcileInterval,
                                             new AtomicReference<>(new CoordinatorState.Dormant()));
    }

    sealed interface CoordinatorState {
        default void onTopologyChange(TopologyChangeNotification notification) {}

        default Map<TaskGroup, TaskAssignmentValue> assignments() {
            return Map.of();
        }

        default Result<Unit> reassign(TaskGroup group, NodeId target) {
            return CoordinatorError.NOT_LEADER.result();
        }

        record Dormant() implements CoordinatorState{}

        record Active(NodeId self,
                      ClusterNode<KVCommand<AetherKey>> clusterNode,
                      KVStore<AetherKey, AetherValue> kvStore,
                      TopologyManager topologyManager,
                      Map<TaskGroup, TaskAssignmentValue> assignmentMap,
                      Map<TaskGroup, Set<NodeId>> failedNodes,
                      CancellableTask reconcileTimer) implements CoordinatorState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override public void onTopologyChange(TopologyChangeNotification notification) {
                switch (notification){
                    case NodeRemoved(NodeId removedNode, _) -> handleNodeDeparture(removedNode);
                    case NodeDown(NodeId downNode, _) -> handleNodeDeparture(downNode);
                    default -> {}
                }
            }

            @Override public Map<TaskGroup, TaskAssignmentValue> assignments() {
                return Map.copyOf(assignmentMap);
            }

            @Override public Result<Unit> reassign(TaskGroup group, NodeId target) {
                return writeAssignment(group, target);
            }

            void reconcile() {
                var healthyNodes = collectHealthyCoreNodes();
                if (healthyNodes.isEmpty()) {
                    log.warn("No healthy core nodes available for task assignment");
                    return;
                }
                readCurrentAssignments();
                var needsAssignment = identifyGroupsNeedingAssignment(healthyNodes);
                if (needsAssignment.isEmpty()) {
                    log.debug("All task groups properly assigned");
                    return;
                }
                assignGroups(needsAssignment, healthyNodes);
            }

            void startReconcileTimer(TimeSpan interval) {
                reconcileTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, interval));
            }

            void cancelReconcileTimer() {
                reconcileTimer.cancel();
            }

            private void handleNodeDeparture(NodeId departedNode) {
                log.info("Node {} departed, checking for orphaned task assignments", departedNode);
                reconcile();
            }

            private void readCurrentAssignments() {
                assignmentMap.clear();
                kvStore.forEach(TaskAssignmentKey.class,
                                TaskAssignmentValue.class,
                                (key, value) -> assignmentMap.put(key.taskGroup(), value));
            }

            private List<TaskGroup> identifyGroupsNeedingAssignment(List<NodeId> healthyNodes) {
                var healthySet = new HashSet<>(healthyNodes);
                var needsAssignment = new ArrayList<TaskGroup>();
                for (var group : TaskGroup.values()) {
                    var assignment = assignmentMap.get(group);
                    if (assignment == null) {
                        needsAssignment.add(group);
                        continue;
                    }
                    if (!healthySet.contains(assignment.assignedTo())) {
                        log.info("Task group {} orphaned (node {} not in topology), will reassign",
                                 group,
                                 assignment.assignedTo());
                        needsAssignment.add(group);
                        continue;
                    }
                    if (assignment.status() == AssignmentStatus.FAILED) {
                        trackFailedNode(group, assignment.assignedTo());
                        log.info("Task group {} failed on node {}, will reassign", group, assignment.assignedTo());
                        needsAssignment.add(group);
                    }
                }
                return needsAssignment;
            }

            private void assignGroups(List<TaskGroup> groups, List<NodeId> healthyNodes) {
                for (var group : groups) {
                    var target = selectLeastLoadedNode(group, healthyNodes).or(self);
                    log.info("Assigning task group {} to node {}", group, target);
                    writeAssignment(group, target).onFailure(cause -> log.error("Failed to assign task group {}: {}",
                                                                                group,
                                                                                cause.message()));
                }
            }

            private Option<NodeId> selectLeastLoadedNode(TaskGroup group, List<NodeId> healthyNodes) {
                var cooldownExpiry = System.currentTimeMillis() - FAILURE_COOLDOWN_MS;
                var recentlyFailed = failedNodes.getOrDefault(group, Set.of());
                return Option.from(healthyNodes.stream().filter(node -> !isRecentlyFailed(node,
                                                                                          recentlyFailed,
                                                                                          cooldownExpiry))
                                                      .min(Comparator.<NodeId, Long>comparing(node -> countActiveAssignments(node))
                                                                     .thenComparing(Comparator.naturalOrder())));
            }

            private boolean isRecentlyFailed(NodeId node, Set<NodeId> recentlyFailed, long cooldownExpiry) {
                if (!recentlyFailed.contains(node)) {return false;}
                var assignment = assignmentMap.values().stream()
                                                     .filter(v -> v.assignedTo().equals(node) && v.status() == AssignmentStatus.FAILED)
                                                     .findFirst();
                return assignment.map(v -> v.assignedAtMs() > cooldownExpiry).orElse(false);
            }

            private long countActiveAssignments(NodeId node) {
                return assignmentMap.values().stream()
                                           .filter(v -> v.assignedTo().equals(node))
                                           .filter(v -> v.status() == AssignmentStatus.ACTIVE || v.status() == AssignmentStatus.ASSIGNED)
                                           .count();
            }

            private void trackFailedNode(TaskGroup group, NodeId node) {
                failedNodes.computeIfAbsent(group, _ -> ConcurrentHashMap.newKeySet()).add(node);
            }

            private List<NodeId> collectHealthyCoreNodes() {
                return topologyManager.topology().stream()
                                               .filter(id -> !topologyManager.isPassive(id))
                                               .sorted()
                                               .toList();
            }

            private Result<Unit> writeAssignment(TaskGroup group, NodeId target) {
                var key = TaskAssignmentKey.taskAssignmentKey(group);
                var value = TaskAssignmentValue.taskAssignmentValue(target);
                assignmentMap.put(group, value);
                var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
                return clusterNode.apply(List.of(command)).await()
                                        .map(_ -> Unit.unit())
                                        .onFailure(cause -> log.error("Consensus proposal failed for task group {} assignment: {}",
                                                                      group,
                                                                      cause.message()));
            }
        }
    }

    enum CoordinatorError implements Cause {
        NOT_LEADER("Task assignment coordinator is not active (not leader)");
        private final String message;
        CoordinatorError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record taskAssignmentCoordinator(NodeId self,
                                     ClusterNode<KVCommand<AetherKey>> clusterNode,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     TopologyManager topologyManager,
                                     TimeSpan reconcileInterval,
                                     AtomicReference<CoordinatorState> state) implements TaskAssignmentCoordinator {
        private static final Logger log = LoggerFactory.getLogger(taskAssignmentCoordinator.class);

        @Override public void onLeaderChange(LeaderChange leaderChange) {
            if (leaderChange.localNodeIsLeader()) {
                log.info("Node {} became leader, activating task assignment coordinator", self);
                var activeState = new CoordinatorState.Active(self,
                                                              clusterNode,
                                                              kvStore,
                                                              topologyManager,
                                                              new ConcurrentHashMap<>(),
                                                              new ConcurrentHashMap<>(),
                                                              CancellableTask.cancellableTask());
                state.set(activeState);
                activeState.reconcile();
                activeState.startReconcileTimer(reconcileInterval);
            } else {
                log.info("Node {} is not leader, deactivating task assignment coordinator", self);
                deactivateCurrentState();
                state.set(new CoordinatorState.Dormant());
            }
        }

        @Override public void onTopologyChange(TopologyChangeNotification notification) {
            state.get().onTopologyChange(notification);
        }

        @Override public Map<TaskGroup, TaskAssignmentValue> assignments() {
            return state.get().assignments();
        }

        @Override public Result<Unit> reassign(TaskGroup group, NodeId target) {
            return state.get().reassign(group, target);
        }

        private void deactivateCurrentState() {
            var current = state.get();
            if (current instanceof CoordinatorState.Active active) {active.cancelReconcileTimer();}
        }
    }
}
