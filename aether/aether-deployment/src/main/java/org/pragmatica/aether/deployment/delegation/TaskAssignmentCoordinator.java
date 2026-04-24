// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Leader-side component that manages task group assignments. Only active on the leader node.
///
/// Built on the [`Fsm`] state machine — two states (`Dormant`, `Active`) with CAS-guarded
/// transitions on caller thread. Dormant is a per-FSM singleton; Active is a fresh record per
/// entry carrying the leader-tenure's working state (assignment map, failed-node tracking,
/// reconcile timer).
public sealed interface TaskAssignmentCoordinator {
    TimeSpan DEFAULT_RECONCILE_INTERVAL = timeSpan(5).seconds();

    long FAILURE_COOLDOWN_MS = 30_000L;

    @Contract
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @Contract
    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification notification);

    Map<TaskGroup, TaskAssignmentValue> assignments();

    Result<Unit> reassign(TaskGroup group, NodeId target);

    static TaskAssignmentCoordinator noOp() {
        return new NoOpCoordinator();
    }

    record NoOpCoordinator() implements TaskAssignmentCoordinator {
        @Override public void onLeaderChange(LeaderChange leaderChange) {}
        @Override public void onTopologyChange(TopologyChangeNotification notification) {}
        @Override public Map<TaskGroup, TaskAssignmentValue> assignments() { return Map.of(); }
        @Override public Result<Unit> reassign(TaskGroup group, NodeId target) { return Result.unitResult(); }
    }

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
        var ctxHolder = new AtomicReference<Context>();
        Function<Fsm<CoordinatorState, ClusterFsmEvent>, CoordinatorState> initialStateFactory =
            f -> buildContextAndInitialState(ctxHolder, f, self, clusterNode, kvStore,
                                             topologyManager, reconcileInterval);
        var fsm = Fsm.fsm("task-assignment", self.id(), initialStateFactory);
        return new taskAssignmentCoordinator(ctxHolder.get(), fsm);
    }

    private static CoordinatorState buildContextAndInitialState(AtomicReference<Context> ctxHolder,
                                                                Fsm<CoordinatorState, ClusterFsmEvent> fsm,
                                                                NodeId self,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                                KVStore<AetherKey, AetherValue> kvStore,
                                                                TopologyManager topologyManager,
                                                                TimeSpan reconcileInterval) {
        var ctx = new Context(fsm, self, clusterNode, kvStore, topologyManager, reconcileInterval);
        ctxHolder.set(ctx);
        return ctx.dormant;
    }

    enum CoordinatorError implements Cause {
        NOT_LEADER("Task assignment coordinator is not active (not leader)");

        private final String message;

        CoordinatorError(String message) { this.message = message; }

        @Override public String message() { return message; }
    }

    /// Shared runtime context. Fully immutable: the `Dormant` singleton and the `Fsm` reference
    /// are built inside the constructor via the constructor-driven initial-state factory in
    /// [`taskAssignmentCoordinator`].
    final class Context {
        final Fsm<CoordinatorState, ClusterFsmEvent> fsm;
        final NodeId self;
        final ClusterNode<KVCommand<AetherKey>> clusterNode;
        final KVStore<AetherKey, AetherValue> kvStore;
        final TopologyManager topologyManager;
        final TimeSpan reconcileInterval;
        final Dormant dormant;

        Context(Fsm<CoordinatorState, ClusterFsmEvent> fsm,
                NodeId self,
                ClusterNode<KVCommand<AetherKey>> clusterNode,
                KVStore<AetherKey, AetherValue> kvStore,
                TopologyManager topologyManager,
                TimeSpan reconcileInterval) {
            this.fsm = fsm;
            this.self = self;
            this.clusterNode = clusterNode;
            this.kvStore = kvStore;
            this.topologyManager = topologyManager;
            this.reconcileInterval = reconcileInterval;
            this.dormant = new Dormant(this);
        }
    }

    sealed interface CoordinatorState extends FsmState<CoordinatorState, ClusterFsmEvent> permits Dormant, Active {}

    record Dormant(Context ctx) implements CoordinatorState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<CoordinatorState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(newActive(ctx));
                default -> tx.ignore();
            }
        }

        private static Active newActive(Context ctx) {
            return new Active(ctx,
                              new ConcurrentHashMap<>(),
                              new ConcurrentHashMap<>(),
                              CancellableTask.cancellableTask());
        }
    }

    record Active(Context ctx,
                  Map<TaskGroup, TaskAssignmentValue> assignmentMap,
                  Map<TaskGroup, Set<NodeId>> failedNodes,
                  CancellableTask reconcileTimer) implements CoordinatorState {
        private static final Logger log = LoggerFactory.getLogger(Active.class);

        @Override
        public void onEntry() {
            log.info("Node {} became leader, activating task assignment coordinator", ctx.self);
            reconcile();
            reconcileTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, ctx.reconcileInterval));
        }

        @Override
        public void onExit() {
            log.info("Node {} no longer leader, deactivating task assignment coordinator", ctx.self);
            reconcileTimer.cancel();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<CoordinatorState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant);
                case ClusterFsmEvent.NodeGone _ -> handleNodeDeparture();
                default -> tx.ignore();
            }
        }

        private void handleNodeDeparture() {
            log.info("Node departed; checking for orphaned task assignments");
            reconcile();
        }

        Result<Unit> reassign(TaskGroup group, NodeId target) {
            return writeAssignment(group, target);
        }

        Map<TaskGroup, TaskAssignmentValue> assignmentsSnapshot() {
            return Map.copyOf(assignmentMap);
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

        private void readCurrentAssignments() {
            assignmentMap.clear();
            ctx.kvStore.forEach(TaskAssignmentKey.class,
                                TaskAssignmentValue.class,
                                (key, value) -> assignmentMap.put(key.taskGroup(), value));
        }

        private List<TaskGroup> identifyGroupsNeedingAssignment(List<NodeId> healthyNodes) {
            var healthySet = new HashSet<>(healthyNodes);
            return java.util.Arrays.stream(TaskGroup.values())
                                   .filter(group -> needsReassignment(group, healthySet))
                                   .toList();
        }

        private boolean needsReassignment(TaskGroup group, Set<NodeId> healthySet) {
            return Option.option(assignmentMap.get(group))
                         .fold(() -> true, assignment -> isOrphanedOrFailed(group, assignment, healthySet));
        }

        private boolean isOrphanedOrFailed(TaskGroup group, TaskAssignmentValue assignment, Set<NodeId> healthySet) {
            if (!healthySet.contains(assignment.assignedTo())) {
                log.info("Task group {} orphaned (node {} not in topology), will reassign",
                         group, assignment.assignedTo());
                return true;
            }
            if (assignment.status() == AssignmentStatus.FAILED) {
                trackFailedNode(group, assignment.assignedTo());
                log.info("Task group {} failed on node {}, will reassign", group, assignment.assignedTo());
                return true;
            }
            return false;
        }

        private void assignGroups(List<TaskGroup> groups, List<NodeId> healthyNodes) {
            var commands = new ArrayList<KVCommand<AetherKey>>();
            for (var group : groups) {
                var target = selectLeastLoadedNode(group, healthyNodes).or(ctx.self);
                log.info("Assigning task group {} to node {}", group, target);
                var key = TaskAssignmentKey.taskAssignmentKey(group);
                var value = TaskAssignmentValue.taskAssignmentValue(target);
                assignmentMap.put(group, value);
                commands.add(new KVCommand.Put<>(key, value));
            }
            if (!commands.isEmpty()) {
                ctx.clusterNode.apply(commands)
                               .onFailure(cause -> log.error("Consensus proposal failed for task assignments: {}",
                                                             cause.message()));
            }
        }

        private Option<NodeId> selectLeastLoadedNode(TaskGroup group, List<NodeId> healthyNodes) {
            var cooldownExpiry = System.currentTimeMillis() - FAILURE_COOLDOWN_MS;
            var recentlyFailed = failedNodes.getOrDefault(group, Set.of());
            return Option.from(healthyNodes.stream()
                                           .filter(node -> !isRecentlyFailed(node, recentlyFailed, cooldownExpiry))
                                           .min(Comparator.<NodeId, Long>comparing(this::countActiveAssignments)
                                                          .thenComparing(Comparator.naturalOrder())));
        }

        private boolean isRecentlyFailed(NodeId node, Set<NodeId> recentlyFailed, long cooldownExpiry) {
            if (!recentlyFailed.contains(node)) {
                return false;
            }
            return Option.from(assignmentMap.values().stream()
                                            .filter(v -> v.assignedTo().equals(node) && v.status() == AssignmentStatus.FAILED)
                                            .findFirst())
                         .map(v -> v.assignedAtMs() > cooldownExpiry)
                         .or(false);
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
            return ctx.topologyManager.topology().stream()
                                      .filter(id -> !ctx.topologyManager.isPassive(id))
                                      .sorted()
                                      .toList();
        }

        private Result<Unit> writeAssignment(TaskGroup group, NodeId target) {
            var key = TaskAssignmentKey.taskAssignmentKey(group);
            var value = TaskAssignmentValue.taskAssignmentValue(target);
            assignmentMap.put(group, value);
            var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
            ctx.clusterNode.apply(List.of(command))
                           .onFailure(cause -> log.error("Consensus proposal failed for task group {} assignment: {}",
                                                         group, cause.message()));
            return Result.unitResult();
        }
    }

    record taskAssignmentCoordinator(Context ctx, Fsm<CoordinatorState, ClusterFsmEvent> fsm) implements TaskAssignmentCoordinator {
        @Override
        public void onLeaderChange(LeaderChange leaderChange) {
            fsm.dispatch(new ClusterFsmEvent.LeaderChange(leaderChange.leaderId(), leaderChange.localNodeIsLeader()));
        }

        @Override
        public void onTopologyChange(TopologyChangeNotification notification) {
            switch (notification) {
                case NodeRemoved(NodeId node, var topology) ->
                    fsm.dispatch(new ClusterFsmEvent.NodeGone(node, topology));
                case NodeDown(NodeId node, var topology) -> {
                    if (topology.isEmpty()) {
                        fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
                    } else {
                        fsm.dispatch(new ClusterFsmEvent.NodeGone(node, topology));
                    }
                }
                default -> {
                    // NodeAdded is not interesting for assignment coordination.
                }
            }
        }

        @Override
        public Map<TaskGroup, TaskAssignmentValue> assignments() {
            return fsm.current() instanceof Active active ? active.assignmentsSnapshot() : Map.of();
        }

        @Override
        public Result<Unit> reassign(TaskGroup group, NodeId target) {
            return fsm.current() instanceof Active active
                   ? active.reassign(group, target)
                   : CoordinatorError.NOT_LEADER.result();
        }

        Map<TaskGroup, TaskAssignmentValue> activeAssignments() {
            return fsm.current() instanceof Active active ? active.assignmentsSnapshot() : Map.of();
        }
    }
}
